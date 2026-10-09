"""InfraMesh Router (Laya) - FastAPI application.

Exposes the same external contract as the Java Router examples:

    POST /api/v1/route   RoutingRequest -> RoutingResponse
    GET  /api/v1/health  NodeHealthResponse

``/route`` is a routing decision only: it never runs inference and never calls a Worker.
There is intentionally no ``/stream`` endpoint.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import secrets
import shutil
import subprocess
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Optional

import psutil
from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import Settings
from app.laya_router import LayaRouter
from app.models import (
    CpuInfo,
    GpuInfo,
    MemoryInfo,
    NodeHealthResponse,
    RoutingError,
    RoutingRequest,
    RoutingResponse,
    RuntimeInfo,
    SystemInfo,
)
from app.prompt_builder import PromptBuilder
from app.worker_selector import UNPROCESSABLE_ENTITY, WorkerSelector

log = logging.getLogger("router.laya")

API_KEY_HEADER = "X-Infra-Api-Key"
API_PATH_PREFIX = "/api/v1"
MIB_TO_BYTES = 1024 * 1024


def create_app(settings: Optional[Settings] = None, laya_router: Optional[LayaRouter] = None) -> FastAPI:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO").upper(), format="%(asctime)s %(levelname)s %(name)s %(message)s")

    settings = settings or Settings.load()
    # The one Laya adapter for the lifetime of the process - never created per request.
    laya = laya_router or LayaRouter()
    prompt_builder = PromptBuilder()
    worker_selector = WorkerSelector(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if not settings.security_enabled:
            log.warning("security.enabled is false: %s requests are NOT authenticated.", API_PATH_PREFIX)

        # Load (and on first run download) the Laya checkpoint before serving, so the
        # first real routing request does not pay the model-loading cost. The server
        # does not accept requests until this completes; if it fails, startup fails.
        started = time.perf_counter()
        await asyncio.to_thread(laya.warm_up)
        log.info("Laya warm-up completed in %.0f ms; Router is READY.", _elapsed_ms(started))
        yield

    app = FastAPI(title="InfraMesh Router (Laya)", lifespan=lifespan)

    # --- Authentication (same rule as infra-node's NodeApiKeyServletFilter) -------------

    @app.middleware("http")
    async def node_api_key_filter(request: Request, call_next):
        if settings.security_enabled and request.url.path.startswith(API_PATH_PREFIX):
            request_api_key = request.headers.get(API_KEY_HEADER)
            if request_api_key is None or not secrets.compare_digest(
                request_api_key.encode(), settings.api_key.encode()
            ):
                return Response(status_code=HTTPStatus.UNAUTHORIZED)
        return await call_next(request)

    # --- Error responses ----------------------------------------------------------------

    @app.exception_handler(RoutingError)
    async def routing_error_handler(request: Request, error: RoutingError):
        log.warning("Routing failed (%s): %s", error.status, error.message)
        return _error_response(request, error.status, error.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, _: RequestValidationError):
        return _error_response(request, HTTPStatus.BAD_REQUEST, "Malformed RoutingRequest.")

    # --- API ----------------------------------------------------------------------------

    @app.get(API_PATH_PREFIX + "/health", response_model=NodeHealthResponse)
    def health() -> NodeHealthResponse:
        return NodeHealthResponse(
            status="UP" if laya.ready else "DOWN",
            system=SystemInfo(cpu=_cpu_info(), memory=_memory_info(), gpus=_gpu_info()),
            # As in infra-node, a Router's routing decisions are not "active requests"
            # (that counter is for Worker inference).
            runtime=RuntimeInfo(active_requests=0),
        )

    # A plain ``def``: FastAPI runs it in its threadpool, so the blocking Laya predict
    # never stalls the event loop.
    @app.post(API_PATH_PREFIX + "/route", response_model=RoutingResponse)
    def route(request: RoutingRequest) -> RoutingResponse:
        total_started = time.perf_counter()

        selection_started = time.perf_counter()
        eligible = worker_selector.filter_by_hints(request.workers, request.routing)
        selection_ms = _elapsed_ms(selection_started)

        prompt = prompt_builder.build(request)
        if prompt is None:
            raise RoutingError(UNPROCESSABLE_ENTITY, "The routing request contains no message content to route on.")

        laya_started = time.perf_counter()
        decision = laya.classify(prompt)
        laya_ms = _elapsed_ms(laya_started)

        selection_started = time.perf_counter()
        selected = worker_selector.select(decision.tier, eligible)
        selection_ms += _elapsed_ms(selection_started)

        # The user's prompt is never logged.
        fields = {
            "event": "routing_decision",
            "sessionId": request.session_id,
            "layaTier": decision.tier.value,
            "candidateWorkerCount": len(request.workers or []),
            "selectedWorkerId": selected.worker_id,
            "layaLatencyMs": round(laya_ms, 2),
            "workerSelectionLatencyMs": round(selection_ms, 2),
            "totalRoutingLatencyMs": round(_elapsed_ms(total_started), 2),
        }
        log.info(json.dumps(fields))
        if log.isEnabledFor(logging.DEBUG):
            log.debug(json.dumps({"event": "laya_probabilities", "sessionId": request.session_id,
                                  "probabilities": decision.probabilities}))

        return RoutingResponse(worker_id=selected.worker_id)

    return app


def _elapsed_ms(started: float) -> float:
    return (time.perf_counter() - started) * 1000.0


def _error_response(request: Request, status: int, message: str) -> JSONResponse:
    # Same shape as the Spring Boot error body the Java Router examples return.
    return JSONResponse(
        status_code=int(status),
        content={
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "status": int(status),
            "error": HTTPStatus(status).phrase,
            "message": message,
            "path": request.url.path,
        },
    )


# --- Node health collection (NodeHealthResponse.system) ---------------------------------


def _cpu_info() -> CpuInfo:
    return CpuInfo(name=platform.processor() or platform.machine(), usage=round(psutil.cpu_percent(interval=None), 2))


def _memory_info() -> MemoryInfo:
    memory = psutil.virtual_memory()
    return MemoryInfo(total=memory.total, used=memory.total - memory.available, available=memory.available)


def _gpu_info() -> list[GpuInfo]:
    # CPU-only nodes are supported: no nvidia-smi simply means no GPUs are reported.
    if shutil.which("nvidia-smi") is None:
        return []
    try:
        output = subprocess.run(
            ["nvidia-smi", "--query-gpu=index,name,utilization.gpu,memory.total,memory.used,memory.free",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=3, check=True,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []

    gpus = []
    for line in output.splitlines():
        values = [value.strip() for value in line.split(",")]
        if len(values) != 6:
            continue
        try:
            gpus.append(GpuInfo(
                index=int(values[0]), name=values[1], usage=float(values[2]),
                total_memory=int(values[3]) * MIB_TO_BYTES,
                used_memory=int(values[4]) * MIB_TO_BYTES,
                available_memory=int(values[5]) * MIB_TO_BYTES,
            ))
        except ValueError:
            continue
    return gpus


app = create_app()
