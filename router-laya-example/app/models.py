"""InfraMesh Router HTTP contract as Pydantic models.

These mirror the JSON shape of the ``infra-node`` 0.1.1 DTOs
(``com.inframesh.node.dto.*``) field for field. ``infra-node`` stays the source of
truth: when a DTO changes there, change it here.

Mirrored Jackson behaviour:
- property names are the Java record component names (camelCase);
- enums travel as their names, kept here as plain strings;
- unknown properties are ignored, so a newer Console does not break this Router;
- every component is nullable, except that the collections the Java records normalize
  in their compact constructors (``null`` -> empty list) are normalized here too.
"""

from __future__ import annotations

from enum import Enum
from typing import Annotated, Any, Optional

from pydantic import BaseModel, BeforeValidator, ConfigDict
from pydantic.alias_generators import to_camel


class WorkerTier(str, Enum):
    """Request complexity / Worker capability class.

    Internal to this Router - never part of the InfraMesh protocol. Laya's raw string
    never leaves the adapter.
    """

    SMALL = "small"
    MEDIUM = "medium"
    POWERFUL = "powerful"

    @property
    def rank(self) -> int:
        return _TIER_RANK[self]


_TIER_RANK = {WorkerTier.SMALL: 0, WorkerTier.MEDIUM: 1, WorkerTier.POWERFUL: 2}


class RoutingError(Exception):
    """A routing failure reported to Console with an HTTP status (422 / 502), never papered over."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


class _Contract(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


def _null_as_empty_list(value: Any) -> Any:
    return [] if value is None else value


_NullAsEmpty = BeforeValidator(_null_as_empty_list)


# --- com.inframesh.node.dto -------------------------------------------------------------


class ToolCall(_Contract):
    id: Optional[str] = None
    name: Optional[str] = None
    # JsonNode: any JSON value.
    arguments: Any = None


class ChatMessage(_Contract):
    # ChatRole: SYSTEM / USER / ASSISTANT / TOOL
    role: Optional[str] = None
    content: Optional[str] = None
    tool_calls: Annotated[list[ToolCall], _NullAsEmpty] = []
    tool_call_id: Optional[str] = None


class ChatOptions(_Contract):
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    top_p: Optional[float] = None


class ToolDefinition(_Contract):
    name: Optional[str] = None
    description: Optional[str] = None
    # JsonNode: a JSON Schema object.
    parameters: Any = None


class ToolChoice(_Contract):
    # ToolChoiceMode: AUTO / NONE / REQUIRED / TOOL
    mode: Optional[str] = None
    tool_name: Optional[str] = None


# --- com.inframesh.node.dto.router ------------------------------------------------------


class ModelInfo(_Contract):
    provider: Optional[str] = None
    model_name: Optional[str] = None
    # ExecutionLocation: LOCAL / EXTERNAL. Null means unknown and is unsafe for private data.
    execution_location: Optional[str] = None


class WorkerCandidate(_Contract):
    # Long in the contract. Null is rejected: a candidate without an id cannot be selected.
    worker_id: int
    name: Optional[str] = None
    description: Optional[str] = None

    models: Annotated[list[ModelInfo], _NullAsEmpty] = []
    provider: Optional[str] = None

    framework: Optional[str] = None
    framework_version: Optional[str] = None

    current_request_count: Optional[int] = None
    queue_size: Optional[int] = None
    average_latency: Optional[float] = None
    throughput: Optional[float] = None
    error_count: Optional[int] = None

    cpu: Optional[str] = None
    cpu_usage: Optional[float] = None

    memory: Optional[str] = None
    memory_usage: Optional[float] = None

    gpu: Optional[str] = None
    gpu_usage: Optional[float] = None

    vram: Optional[str] = None
    vram_usage: Optional[float] = None


class RoutingHints(_Contract):
    preferred_model: Optional[str] = None
    required_provider: Optional[str] = None
    required_capabilities: Annotated[list[str], _NullAsEmpty] = []
    gpu_required: Optional[bool] = None
    minimum_vram_bytes: Optional[int] = None
    # Missing / null / false all mean "no private routing restriction requested".
    private_data: Optional[bool] = None


class RoutingRequest(_Contract):
    # Context only: Console - not the Router - owns Session Affinity.
    session_id: Optional[str] = None
    messages: Optional[list[ChatMessage]] = None
    options: Optional[ChatOptions] = None
    routing: Optional[RoutingHints] = None
    tools: Annotated[list[ToolDefinition], _NullAsEmpty] = []
    tool_choice: Optional[ToolChoice] = None
    workers: Annotated[list[WorkerCandidate], _NullAsEmpty] = []


class RoutingResponse(_Contract):
    worker_id: int


# --- com.inframesh.node.dto.NodeHealthResponse ------------------------------------------


class CpuInfo(_Contract):
    name: Optional[str] = None
    usage: Optional[float] = None


class MemoryInfo(_Contract):
    total: Optional[int] = None
    used: Optional[int] = None
    available: Optional[int] = None


class GpuInfo(_Contract):
    index: Optional[int] = None
    name: Optional[str] = None
    usage: Optional[float] = None
    total_memory: Optional[int] = None
    used_memory: Optional[int] = None
    available_memory: Optional[int] = None


class SystemInfo(_Contract):
    cpu: CpuInfo
    memory: MemoryInfo
    gpus: list[GpuInfo]


class RuntimeInfo(_Contract):
    active_requests: Optional[int] = None


class NodeHealthResponse(_Contract):
    # NodeStatus: UP / DOWN
    status: str
    system: SystemInfo
    runtime: RuntimeInfo
