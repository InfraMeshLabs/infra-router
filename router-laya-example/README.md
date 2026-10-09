# router-laya-example

> InfraMesh Router reference implementation that uses [Laya](https://pypi.org/project/laya/) to judge request complexity.

`router-laya-example` is a complete, standalone InfraMesh Router. It speaks the same
`infra-node` Router protocol as the Java examples in this repository
(`router-static-example`, `router-spring-ai-example`, `router-jev-example`), so Console
registers and calls it exactly like any other Router — it does not need to know this one is
written in Python.

Laya is a Python library, so this example is a **Python + FastAPI service**, not a Gradle
subproject. It is not included in `settings.gradle` and the root Gradle build ignores it.

## Architecture

```text
InfraMesh Console
        │ RoutingRequest (+ WorkerCandidates)
        ▼
router-laya-example  (FastAPI)
        │
        ├── WorkerSelector   RoutingHints hard filters / soft preference
        ├── PromptBuilder    RoutingRequest.messages → Laya input text
        ├── LayaRouter       Laya Choice → WorkerTier (small / medium / powerful)
        └── WorkerSelector   capability tier + Load Score → one Worker
        │
        ▼ RoutingResponse { workerId }
InfraMesh Console ──▶ Worker ──▶ Inference
```

Responsibilities are deliberately split:

| Component | Decides |
|---|---|
| Laya (`app/laya_router.py`) | What the request *means*: its complexity tier. Nothing else. |
| `WorkerSelector` (`app/worker_selector.py`) | Which Worker: capability tier from configuration, then real-time load. |
| Console / Worker | Everything else. This Router never runs inference, streams, proxies, calls a Worker, polls Worker health, or handles session affinity. |

Laya is asked one Choice question — *"Which worker class is most appropriate for this
request?"* — with `small` / `medium` / `powerful` as options. Only the returned `choice` is
used. `confidence` is **not** used: the current checkpoint warns that its temperatures are
uncalibrated. `probabilities` are logged at `DEBUG` only. Worker runtime metrics are never
handed to Laya.

### Worker selection

1. **RoutingHints**, same policy as the other examples: `requiredProvider`, `gpuRequired`
   and `privateData` are hard filters (`422` when no candidate remains); `preferredModel`
   is a soft preference.
2. **Capability tier.** Each model name is mapped to a tier by the explicit entries in
   `application.yml` (no model name is hardcoded); a Worker's tier is the highest tier among
   its models. `powerful > medium > small`, and a more capable Worker can serve a lower
   request. Among capable Workers, the **closest** tier is used, so a `small` request does
   not occupy a `powerful` Worker while a `small` one is available.
3. **Tier fallback**, when no Worker is capable enough:
   `FALLBACK` (default) uses the highest tier that is available and logs a `WARN`;
   `STRICT` fails with `422`.
4. **Load Score** within the chosen tier: the same weighted score as
   `router-static-example` (`currentRequestCount`, `queueSize`, `averageLatency`,
   `gpuUsage`, `vramUsage`; missing metrics excluded with their weight redistributed;
   deterministic tie-break ending in lowest `workerId`). Lowest score wins.

## Requirements

- Python 3.12
- ~846MB of disk for the Laya checkpoint, downloaded from Hugging Face on first start
- Network access to Hugging Face on first start (not needed once cached)

## Installation

```bash
cd router-laya-example

python3 -m venv .venv
source .venv/bin/activate

python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

## Local Run

```bash
INFRA_NODE_API_KEY=<NODE_API_KEY> \
uvicorn app.main:app --host 0.0.0.0 --port 8093
```

It listens on port `8093` (the Java examples use `8090`–`8092`).

Run the tests (Laya is mocked; no checkpoint is needed):

```bash
python -m pytest
```

## Docker Run

```bash
cd router-laya-example

docker build -t inframesh-router-laya .

docker run --rm -p 8093:8093 \
  -e INFRA_NODE_API_KEY=<NODE_API_KEY> \
  inframesh-router-laya
```

The checkpoint is not baked into the image; it is downloaded when the container starts. To
keep it across runs, mount the cache directory:

```bash
docker run --rm -p 8093:8093 \
  -e INFRA_NODE_API_KEY=<NODE_API_KEY> \
  -v laya-cache:/home/app/.cache \
  inframesh-router-laya
```

## Configuration

Environment variables:

| Variable | Default | Notes |
|---|---|---|
| `INFRA_NODE_API_KEY` | *(unset)* | Node API key issued by Infra Console. Must be a UUID, as in `infra-node` (`infra.node.api-key`). Required while security is enabled. Never commit a real key. |
| `SECURITY_ENABLED` | from `application.yml` (`true`) | `true` or `false`. Overrides `security.enabled`. |
| `ROUTER_LAYA_FALLBACK_POLICY` | from `application.yml` | `STRICT` or `FALLBACK`. |
| `ROUTER_CONFIG` | `./application.yml` | Path to the configuration file. |
| `LOG_LEVEL` | `INFO` | `DEBUG` additionally logs Laya's `probabilities`. |

`application.yml`:

```yaml
security:
  enabled: true                  # /api/v1/* requires X-Infra-Api-Key

router:
  laya:
    fallback-policy: FALLBACK
    default-tier: small          # models matching no rule / Workers with no models
    model-capabilities:          # first matching rule wins, case-insensitive
      - pattern: "gemma3:4b"
        tier: small
      - pattern: "qwen3:8b"
        tier: medium
      - pattern: "qwen3:32b"
        tier: powerful
    weights:                     # must sum to 1.0
      request-count: 0.30
      queue-size: 0.25
      latency: 0.20
      gpu-usage: 0.15
      vram-usage: 0.10
```

### Security

Authentication is never switched off implicitly:

| `security.enabled` | `INFRA_NODE_API_KEY` | Result |
|---|---|---|
| `true` (default) | set | `/api/v1/*` requires `X-Infra-Api-Key`. |
| `true` (default) | missing / blank / not a UUID | **Startup fails** with a configuration error. |
| `false` | *(ignored)* | `/api/v1/*` is not authenticated; a `WARN` is logged at startup. |

Disable it only in a trusted development environment, and only explicitly:

```bash
SECURITY_ENABLED=false uvicorn app.main:app --port 8093
```

What is checked is the `infra-node` `0.1.1` contract the Java examples get from the SDK:
the `X-Infra-Api-Key` header, every path under `/api/v1`, an exact match against the UUID
key, and `401` with an empty body otherwise.

`security.enabled` is **not** part of that contract. It is a setting of this Python example
only — `infra-node` has no authentication mode and no switch to disable its filter — and
Console neither knows about it nor sends anything for it.

### Model capability mapping

Map each model your Workers serve to a tier **explicitly**. How capable a model is depends
on the model, not on its parameter count, so there is no built-in size-based rule and the
shipped entries only illustrate the format — replace them with your own fleet. A model that
is not listed gets `default-tier`.

`pattern` is matched case-insensitively against `ModelInfo.modelName` and may contain glob
wildcards (for example `my-finetune-*`) when you have assessed a whole model family.

## API Contract

Identical to the Java Router examples. The Pydantic models in `app/models.py` mirror the
`infra-node` **`0.1.1`** DTOs (`io.github.inframeshai:inframesh-node:0.1.1`) field for
field; `infra-node` remains the source of truth. This service has no dependency on the Java
artifact — when the contract version changes, `app/models.py` has to be updated by hand.

Laya-specific data (tier, probabilities, scores) is internal and never appears in a request
or response.

| | |
|---|---|
| Routing | `POST /api/v1/route` — `RoutingRequest` → `RoutingResponse` |
| Health | `GET /api/v1/health` — `NodeHealthResponse` |
| Authentication | `X-Infra-Api-Key: <NODE_API_KEY>` on every `/api/v1/*` request; missing or wrong key → `401` with an empty body (see [Security](#security)) |

```bash
curl -s localhost:8093/api/v1/route \
  -H 'X-Infra-Api-Key: <NODE_API_KEY>' -H 'Content-Type: application/json' \
  -d '{
        "sessionId": "session-1",
        "messages": [{"role": "USER", "content": "What is 1 + 1?"}],
        "workers": [
          {"workerId": 1, "models": [{"modelName": "gemma3:4b", "executionLocation": "LOCAL"}], "queueSize": 0},
          {"workerId": 2, "models": [{"modelName": "qwen3:8b", "executionLocation": "LOCAL"}], "queueSize": 0}
        ]
      }'
# {"workerId":1}
```

`RoutingResponse` carries `workerId` only. No Laya-specific field (tier, probabilities) is
ever added to it.

Errors:

| Status | When |
|---|---|
| `400` | Malformed `RoutingRequest`. |
| `401` | Missing or wrong `X-Infra-Api-Key`. |
| `422` | No worker candidates; a hard `RoutingHints` constraint leaves no candidate; no message content to route on; `STRICT` policy and no Worker of a sufficient tier. |
| `502` | The Laya call failed or returned a choice that was not offered. As in `router-spring-ai-example`, an AI failure is reported, not silently replaced by another algorithm. |

Error bodies use the Spring Boot shape (`timestamp`, `status`, `error`, `message`, `path`).

Health follows `NodeHealthResponse` (`status`, `system.cpu`, `system.memory`,
`system.gpus`, `runtime.activeRequests`) with no extra fields. `status` is `UP` once the
Laya model is loaded.

### Logging

One structured line per routing decision. The user's prompt is never logged.

```json
{"event": "routing_decision", "sessionId": "session-1", "layaTier": "small", "candidateWorkerCount": 2, "selectedWorkerId": 1, "layaLatencyMs": 19.21, "workerSelectionLatencyMs": 0.02, "totalRoutingLatencyMs": 19.24}
```

`layaLatencyMs` includes time spent waiting for the predict lock (see below).

## Warm-up

```text
Application start → Laya Router created → warm-up predict("Hello") → model loaded → READY
```

Laya loads its model lazily on the first `predict()` (about 2.5s once the checkpoint is
cached, longer when it must be downloaded). The FastAPI `lifespan` handler creates the
single Laya `Router` and runs one warm-up predict before the server accepts any request, so
no routing request pays that cost. If warm-up fails, startup fails. After warm-up a predict
takes roughly 20–45ms on the development machine.

The Laya `Router` is created once per process and reused for every request.

## Known Limitations

- **Predict is serialized.** Concurrent `predict()` on one Laya instance has not been
  verified as safe, so calls go through a lock. Throughput is one predict at a time per
  process; under concurrency requests queue (8 simultaneous requests finished in ~150ms
  locally). Scale with more processes/replicas — each loads its own copy of the model.
- **Confidence is unused** because the checkpoint's calibration is flagged as invalid;
  routing follows `choice` alone, however close the probabilities are.
- **Only the last user message** is judged. Multi-turn context is a `PromptBuilder` change.
- **Tier mapping is by model name** and has to be maintained by hand in
  `application.yml`; `WorkerCandidate` carries no capability field.
- **`requiredCapabilities` and `minimumVramBytes` are not enforced**, as in the other
  examples — `WorkerCandidate` has nothing to check them against.
- **DIRECT mode only.** The OUTBOUND WebSocket connection is provided by the Java
  `infra-node` SDK and is not implemented here.
- **Health GPU info** is reported for NVIDIA (`nvidia-smi`) only; other hosts report no GPUs.
- **Runtime checkpoint download** (~846MB) happens on first start unless a cache volume is
  mounted.
