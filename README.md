# InfraMesh Router Example

> Reference implementation for building a custom Router node for the InfraMesh distributed AI inference network.

`infra-router` is a reference implementation showing how to build a **Router node** using the `infra-node` SDK.

A Router receives a routing request from InfraMesh Console together with the Workers that are eligible to process the request, analyzes the request and Worker information, selects an appropriate Worker, and returns the routing result.

The internal routing algorithm is completely implementation-specific.

A Router may use:

- AI / LLM-based routing
- Rule-based routing
- Score-based routing
- Model-aware routing
- Provider-aware routing
- Hardware-aware routing
- Load-aware routing
- Cost-aware routing
- Organization-specific routing
- A combination of multiple strategies

Using an AI model inside the Router is **optional**.

This repository serves as an **example and starting point** for implementing custom Router nodes.

---

# Modules

`infra-router` provides **four independent reference Router implementations**, all built on
the same `infra-node` protocol (`RoutingRequest` / `RoutingHints` / `WorkerCandidate` /
`RoutingResponse`). Console talks to any of them the same way — it does not need to know
which implementation, or which language, is behind the endpoint.

Three are modules of the multi-module Gradle project; `router-laya-example` is a standalone
Python application that lives in the same repository but is not a Gradle subproject.

| Example | Runtime | Description |
|---|---|---|
| `router-static-example` | Java / Spring Boot | Static routing example |
| `router-spring-ai-example` | Java / Spring Boot | Spring AI based routing example |
| `router-jev-example` | Java / Spring Boot | Jev based routing example |
| `router-laya-example` | Python / FastAPI | Laya based semantic routing example |

```text
infra-router
│
├── router-spring-ai-example
│   Spring AI + Local AI based routing example
│   → Worker selection is delegated to a chat model.
│
├── router-static-example
│   Deterministic metric-based routing example
│   → Worker selection is a configurable weighted score
│     over Worker runtime metrics. No AI / LLM dependency.
│
├── router-jev-example
│   Jev typed-decision based routing example
│   → Worker selection is a single Choice decision from
│     the Jev (TypeSafe AI) API. No Spring AI dependency.
│
└── router-laya-example
    Laya semantic routing example (Python / FastAPI)
    → Laya judges request complexity; a deterministic
      selector maps it to a Worker. Not a Gradle module.
```

## router-spring-ai-example

```text
RoutingRequest
       ↓
Worker candidates + request context
       ↓
Local AI (Spring AI + Ollama)
       ↓
AI judges the best Worker
       ↓
RoutingResponse
```

Good for showing request-context-aware routing: the model can reason about request intent,
model/provider constraints, and Worker runtime state together.

Run it:

```bash
./gradlew :router-spring-ai-example:bootRun
```

It listens on port `8090` and requires a local Ollama runtime (see `application.yml` in the
module for the model/host configuration).

## router-static-example

```text
RoutingRequest
       ↓
Worker Runtime Metrics
       ↓
Deterministic Weighted Score
       ↓
Lowest-load Worker selected
       ↓
RoutingResponse
```

No LLM, no Spring AI dependency, fast and fully predictable. Good as a lightweight default
or as a fallback reference when an AI runtime is unavailable.

Run it:

```bash
./gradlew :router-static-example:bootRun
```

It listens on port `8091`.

### Static Router scoring

The Static Router computes a **Load Score** from five Worker runtime metrics:
`currentRequestCount`, `queueSize`, `averageLatency`, `gpuUsage`, `vramUsage`. Each metric
is normalized to a `0.0–1.0` range, then combined with a configurable weight. **The Worker
with the lowest score is selected** — a low score means more spare capacity.

- `currentRequestCount`, `queueSize`, `averageLatency` have no fixed maximum, so they are
  normalized relative to the highest value among the current candidate set.
- `gpuUsage`, `vramUsage` are already reported as a `0–100` percentage, so they are
  normalized by dividing by `100`.
- A metric that is `null` for a Worker (most commonly `gpuUsage`/`vramUsage` on a CPU-only
  Worker) is excluded from that Worker's score, and its weight is redistributed
  proportionally across that Worker's remaining available metrics — a missing metric is
  never defaulted to `0`, which would otherwise make a Worker look artificially better
  just because it doesn't report a metric.
- Ties (including "no metric available at all") are broken deterministically: lowest score
  → lowest `queueSize` → lowest `currentRequestCount` → lowest `averageLatency` → lowest
  `workerId`. There is no random tie-break, so identical input always produces the same
  routing decision.

Weights are configured in `router-static-example/src/main/resources/application.yml`
instead of being hardcoded, and must sum to `1.0` (validated at startup):

```yaml
router:
  static:
    weights:
      request-count: 0.30
      queue-size: 0.25
      latency: 0.20
      gpu-usage: 0.15
      vram-usage: 0.10
```

`RoutingHints` are still respected before scoring: `requiredProvider`, `gpuRequired` and
`privateData` are hard filters (a Worker that fails one is removed from candidates, and
routing fails with `422` if no candidate remains), while `preferredModel` is a soft
preference that only narrows the candidate set when at least one remaining candidate
actually supports that model.

## router-jev-example

```text
RoutingRequest
       ↓
RoutingHints filtering (Java)
       ↓
Eligible Workers → Jev Choice question
       ↓
Jev API (typed decision)
       ↓
Chosen Worker validated against candidates
       ↓
RoutingResponse
```

[Jev](https://docs.typesafe.ai) is TypeSafe AI's "System One" model: instead of generating
text, it answers typed questions (Choice / Score / Noul) over a piece of state and returns
a selected option with calibrated probabilities and confidence. This Router asks a single
Choice question — "which of these eligible Workers fits this request best" — with one
option per eligible candidate (`worker_<id>`) and that candidate's
`currentRequestCount`/`queueSize`/`averageLatency`/`gpuUsage`/`vramUsage` as state. Jev is
called directly over HTTP (`POST /v1/systemone`) via `WebClient` — it is not wrapped as a
Spring AI `ChatModel`, since it isn't a chat/completion model.

Run it:

```bash
JEV_API_KEY=... ./gradlew :router-jev-example:bootRun
```

It listens on port `8092`. Configuration (`router-jev-example/src/main/resources/application.yml`):

```yaml
jev:
  base-url: ${JEV_BASE_URL:https://api.typesafe.ai}
  api-key: ${JEV_API_KEY:}
  model: ${JEV_MODEL:jev-latest}
  timeout: ${JEV_TIMEOUT:2s}
```

| Variable | Default | Notes |
|---|---|---|
| `JEV_API_KEY` | *(empty)* | Required to actually call Jev. Get one from the [TypeSafe console](https://console.typesafe.ai/keys). Never commit a real key. |
| `JEV_BASE_URL` | `https://api.typesafe.ai` | Jev API base URL. |
| `JEV_MODEL` | `jev-latest` | Jev model identifier. |
| `JEV_TIMEOUT` | `2s` | Hard timeout on the Jev call — Routing must not hang on an external API. |

### Policy split and fallback

Exactly like the other two Router examples, `requiredProvider`, `gpuRequired` and
`privateData` are enforced as hard filters **in Java, before Jev is ever called**, and
`preferredModel` is resolved as a soft preference in Java too. Jev is a decision layer over
already-eligible candidates, never the thing that decides whether a policy is satisfied —
and its answer is only trusted if it names a Worker that was actually offered.

Jev is an external HTTP call, so it can time out, rate-limit, or fail outright. Rather than
let a routing decision hang or fail the whole request over a transient API problem, any Jev
failure (timeout, connection error, non-2xx, unusable response) **or an unrecognized Worker
choice** falls back to a small deterministic ordering: lowest `queueSize` → lowest
`currentRequestCount` → lowest `averageLatency` → lowest `workerId`. This is intentionally
*not* the full weighted/configurable algorithm from `router-static-example` — copying that
module's scoring here would create two competing sources of truth for the same policy — so
the fallback here is deliberately minimal, and every fallback is logged at `WARN` rather
than happening silently.

## router-laya-example

```text
RoutingRequest
       ↓
RoutingHints filtering (Python)
       ↓
Last user message → Laya Choice question
       ↓
Request tier: small / medium / powerful
       ↓
Worker capability tier + Weighted Load Score
       ↓
RoutingResponse
```

[Laya](https://pypi.org/project/laya/) is a local, non-autoregressive decision model
distributed as a Python library, so this example is a standalone **Python + FastAPI**
service rather than a Spring Boot module. It implements the same Router protocol itself —
`POST /api/v1/route`, `GET /api/v1/health`, `X-Infra-Api-Key` — instead of sitting behind a
Java Router, so Console registers it like any other Router.

Laya only judges how complex the request is. Which Worker serves it is decided in code:
each Worker's capability tier comes from a configurable model-name mapping, and Workers of
the same tier are ranked with the same Load Score as `router-static-example`. Worker
runtime metrics are never handed to Laya.

Run it:

```bash
cd router-laya-example
python3 -m venv .venv && source .venv/bin/activate
python -m pip install -r requirements.txt
INFRA_NODE_API_KEY=... uvicorn app.main:app --host 0.0.0.0 --port 8093
```

It listens on port `8093`. Authentication is on by default and startup fails without
`INFRA_NODE_API_KEY`; it can only be turned off explicitly (`SECURITY_ENABLED=false`). The Laya checkpoint (~846MB) is downloaded on first start and
loaded during startup warm-up, before the Router accepts requests. See
[`router-laya-example/README.md`](router-laya-example/README.md) for configuration, Docker,
the tier fallback policy and known limitations.

None of the four Router implementations is "better" in general — they are reference
examples of four different routing strategies (local AI, deterministic metrics, an
external typed-decision API, and a local semantic classifier), and organizations can pick,
extend, or combine any of these approaches for their own Router nodes.

| Router | Decision |
|---|---|
| `router-spring-ai-example` | Local LLM (Spring AI + Ollama) |
| `router-static-example` | Weighted Worker metrics |
| `router-jev-example` | Jev typed-decision API (Choice), with deterministic fallback |
| `router-laya-example` | Laya request-complexity tier, then weighted Worker metrics |

---

# Overview

InfraMesh separates infrastructure management, routing decisions, and inference execution into independent components.

```text
                 +----------------------+
                 |    Infra Console     |
                 |    Control Plane     |
                 +----------+-----------+
                            |
                            | RoutingRequest
                            | + WorkerCandidates
                            |
                 +----------v-----------+
                 |    Infra Router      |
                 |   Routing Engine     |
                 +----------+-----------+
                            |
                            | RoutingResponse
                            | selected workerId
                            |
                 +----------v-----------+
                 |    Infra Console     |
                 +----------+-----------+
                            |
                    Selected Worker
                            |
               +------------+------------+
               |                         |
        +------v------+           +------v------+
        |  Worker A   |           |  Worker B   |
        +-------------+           +-------------+
```

Each component has a clear responsibility:

- **Console** manages organizations, teams, nodes, Worker availability, and routing configuration.
- **Router** analyzes a request and selects one Worker from the candidates provided by Console.
- **Worker** performs the actual AI inference.
- **infra-node** defines the common SDK, DTOs, health contracts, authentication integration, node specifications, and the Outbound Connection SDK shared by Routers and Workers.

The Router does **not execute the final AI inference request**.

The Router also does **not call the selected Worker directly**.

The execution flow is:

```text
Client
   │
   ▼
Console
   │
   │ RoutingRequest
   │ + WorkerCandidates
   ▼
Router
   │
   │ RoutingResponse
   │ workerId
   ▼
Console
   │
   ▼
Worker
   │
   ▼
AI Runtime
```

---

# infra-node Dependency

A Router implementation should depend on the **InfraMesh Node SDK (`infra-node`)**.

The SDK defines the common contracts shared between Console and Router implementations.

These include:

- `RoutingRequest`
- `RoutingHints`
- `WorkerCandidate`
- `RoutingResponse`
- Common request DTOs
- Chat messages and options
- Tool-related request information
- Node health contracts
- Authentication support
- Common node integration components
- Outbound Connection SDK — the persistent Console connection (WebSocket client, authentication header, heartbeat, reconnect, lifecycle) used in OUTBOUND mode

`infra-node` is published to Maven Central. The current protocol contract is **`0.1.1`**:

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.inframeshai:inframesh-node:0.1.1'
}
```

A Router that is not written in Java (see `router-laya-example`) cannot use the artifact and
instead mirrors the same `0.1.1` JSON contract field for field.

Custom Router implementations should always use the contracts provided by `infra-node`.

Do not create incompatible Router protocol DTOs inside individual Router implementations.

```text
                    infra-node
                        │
                 Router Protocol
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
          Console              Custom Router
```

`infra-node` is the source of truth for communication between Console and Router.

---

# Router Responsibilities

An InfraMesh Router has two primary external capabilities:

```text
Router
├── Health   GET  /api/v1/health
│
└── Route    POST /api/v1/route
```

The Router is responsible for:

```text
Receive RoutingRequest
        │
        ▼
Inspect Request
        │
        ▼
Inspect RoutingHints
        │
        ▼
Inspect WorkerCandidates
        │
        ▼
Apply Custom Routing Logic
        │
        ▼
Select Worker
        │
        ▼
Return RoutingResponse
```

The Router is **not responsible for Worker discovery or inference execution**.

---

# Health

Routers must expose health information so InfraMesh Console can determine whether the Router is available.

```http
GET /api/v1/health
X-Infra-Api-Key: <NODE_API_KEY>
```

A Java Router does not implement this endpoint: `infra-node` auto-configures it
(`HealthCheckController`) and returns `NodeHealthResponse`:

```json
{
  "status": "UP",
  "system": {
    "cpu":    { "name": "…", "usage": 12.5 },
    "memory": { "total": 0, "used": 0, "available": 0 },
    "gpus":   [ { "index": 0, "name": "…", "usage": 40.0,
                  "totalMemory": 0, "usedMemory": 0, "availableMemory": 0 } ]
  },
  "runtime": { "activeRequests": 0 }
}
```

`status` is `UP` or `DOWN`. Usage values are percentages (`0`–`100`), memory values are
bytes, and `gpus` is empty on a CPU-only node. `runtime.activeRequests` counts Worker
inference only, so it stays `0` on a Router.

Conceptually:

```text
Infra Console
      │
      │ GET /api/v1/health
      ▼
    Router
      │
      ▼
NodeHealthResponse
```

Console can periodically call this endpoint and maintain the Router runtime state.

Custom Router implementations should reuse the health components provided by `infra-node` rather than defining incompatible Router-specific health contracts.

---

# Route

Routing is performed through a single route request.

```http
POST /api/v1/route
X-Infra-Api-Key: <NODE_API_KEY>
```

`/route` means "choose a Worker for this request". It is not `POST /api/v1/invoke`, which is
the **Worker** inference endpoint — a Router never exposes or calls it.

Unlike health, `infra-node` does not provide this endpoint: each Router implements the
controller itself, under the `/api/v1` prefix so that the SDK's API key filter covers it.

The request uses:

```java
com.inframesh.node.dto.router.RoutingRequest
```

and the response uses:

```java
com.inframesh.node.dto.router.RoutingResponse
```

Conceptually:

```text
Infra Console
      │
      │ RoutingRequest
      ▼
    Router
      │
      ├── Analyze Request
      ├── Inspect RoutingHints
      ├── Inspect WorkerCandidates
      ├── Apply Routing Algorithm
      ├── Select Worker
      └── Validate Selection
      │
      ▼
RoutingResponse
      │
      │ selected workerId
      ▼
Infra Console
```

All routing decisions are completed through this single route flow.

---

# No Router Streaming API

The Router does not expose a separate streaming routing API.

The following endpoint is **not required**:

```text
POST /api/v1/stream
```

Routing is a single decision:

```text
Request
   │
   ▼
Router
   │
   ▼
Worker Selection
   │
   ▼
RoutingResponse
```

Even when the original inference request is streaming, Router invocation itself remains non-streaming.

For a streaming inference request:

```text
Streaming Client Request
          │
          ▼
       Console
          │
          │ Filter Workers capable
          │ of streaming
          ▼
   RoutingRequest
   + WorkerCandidates
          │
          ▼
        Router
          │
          │ POST /api/v1/route
          ▼
   RoutingResponse
          │
          ▼
       Console
          │
          │ Worker streaming request
          ▼
        Worker
          │
          ▼
   Streaming Response
```

The Router only selects the Worker.

The Worker performs the actual streaming inference.

---

# RoutingRequest

Router requests use the `RoutingRequest` contract provided by `infra-node`.

In `infra-node` `0.1.1` a `RoutingRequest` contains:

```text
RoutingRequest
├── sessionId     String, nullable — propagated for context only;
│                 Session Affinity is owned by Console, not the Router
├── messages      List<ChatMessage>
│                   role (SYSTEM | USER | ASSISTANT | TOOL), content,
│                   toolCalls [ { id, name, arguments } ], toolCallId
├── options       ChatOptions, nullable — temperature, maxTokens, topP
├── routing       RoutingHints, nullable
├── tools         List<ToolDefinition> — name, description, parameters (JSON Schema)
├── toolChoice    ToolChoice, nullable — mode (AUTO | NONE | REQUIRED | TOOL), toolName
└── workers       List<WorkerCandidate>
```

Field names are the JSON property names; enums are sent as their names. A missing or `null`
`tools` / `workers` list is read as an empty list.

For example:

```json
{
  "sessionId": "session-1",
  "messages": [ { "role": "USER", "content": "Spring AI가 뭐야?" } ],
  "options": { "temperature": 0.7, "maxTokens": 1024, "topP": null },
  "routing": { "preferredModel": "qwen3", "requiredProvider": "OLLAMA" },
  "tools": [],
  "toolChoice": null,
  "workers": [ { "workerId": 17, "provider": "OLLAMA", "queueSize": 1 } ]
}
```

The Router should use the actual `RoutingRequest` record provided by the current `infra-node` dependency rather than redefining it locally.

Request information should remain available to the routing implementation so that custom Routers can make decisions based on the characteristics of the request.

For example, a Router may consider:

```text
Request content
Tool requirements
Request options
Routing hints
Worker models and provider
Worker runtime state
```

The exact routing algorithm remains implementation-specific.

---

# RoutingHints

`RoutingHints` represents routing preferences or constraints supplied with an inference request.

In `infra-node` `0.1.1` the fields are:

```text
preferredModel         String
requiredProvider       String
requiredCapabilities   List<String>   (null is read as an empty list)
gpuRequired            Boolean
minimumVramBytes       Long
privateData            Boolean        (missing / null / false = no restriction)
```

Every field is optional. When `privateData` is `true`, the Router must not select a Worker
that could send the request's data to an `EXTERNAL` AI provider.

`requiredCapabilities` and `minimumVramBytes` are part of the contract, but `WorkerCandidate`
currently has no capability list and no numeric VRAM capacity to check them against, so none
of the reference Routers enforce them.

Routing hints should describe **what the request prefers or requires**.

They should not contain Worker runtime information.

The distinction is:

```text
RoutingHints
    │
    └── What does this request need?


WorkerCandidate
    │
    └── What can this Worker provide
        and what is its current state?
```

Routers can use these two sources together when selecting a Worker.

---

# Preferred vs Required Routing Hints

Router implementations should distinguish between preferences and hard constraints when the current `RoutingHints` contract provides that distinction.

For example:

```text
requiredProvider
        │
        ▼
Hard Constraint
        │
        ▼
Remove Workers that do not satisfy it


preferredModel
        │
        ▼
Soft Preference
        │
        ▼
Prefer matching Workers among
the remaining candidates
```

A useful conceptual routing pipeline is:

```text
WorkerCandidates
       │
       ▼
Hard Constraints
       │
       ▼
Eligible Candidates
       │
       ▼
Preferences
       │
       ▼
Runtime / Load Evaluation
       │
       ▼
Selected Worker
```

The exact interpretation of each hint should follow the `infra-node` contract.

---

# WorkerCandidate

Console determines which Workers are eligible for the current request.

It then converts those Workers into the common:

```java
com.inframesh.node.dto.router.WorkerCandidate
```

contract and includes them in the routing request.

Therefore, the Router does **not discover Workers independently**.

```text
Console

Worker A
Worker B
Worker C
Worker D

   │
   │ Apply availability,
   │ team, drain mode,
   │ streaming and other
   │ Console-side constraints
   ▼

Worker A
Worker C

   │
   ▼

WorkerCandidates
├── Worker A
└── Worker C

   │
   ▼

RoutingRequest

   │
   ▼

Router
```

The Router must select a Worker only from the candidates supplied by Console.

---

# Worker Runtime Information

`WorkerCandidate` exposes the Worker identity, model and runtime information required for routing decisions.

In `infra-node` `0.1.1` the fields are:

```text
workerId              Long
name                  String
description           String

models                List<ModelInfo>   (null is read as an empty list)
                        provider, modelName,
                        executionLocation (LOCAL | EXTERNAL, null = unknown)
provider              String

framework             String
frameworkVersion      String

currentRequestCount   Integer
queueSize             Integer
averageLatency        Number
throughput            Number
errorCount            Long

cpu                   String   (description)
cpuUsage              Number   (0–100 %)

memory                String   (description)
memoryUsage           Number   (0–100 %)

gpu                   String   (description; absent on a CPU-only Worker)
gpuUsage              Number   (0–100 %)

vram                  String   (description such as "32GB", not bytes)
vramUsage             Number   (0–100 %)
```

Any field other than `workerId` may be `null` — most commonly `gpu` / `gpuUsage` / `vram` /
`vramUsage` on a CPU-only Worker — and a Router must not treat a missing metric as `0`.
An unknown `executionLocation` must be treated as unsafe for `privateData` requests.

This allows Router implementations to make decisions using both the Worker's models and its dynamic runtime state.

For example:

```text
Worker A
────────────────────
models          qwen3, gemma3
provider        OLLAMA
requests        2
queue           1
latency         180 ms
throughput      4.2 req/s
gpuUsage        45%
vramUsage       52%


Worker B
────────────────────
models          qwen3
provider        VLLM
requests        5
queue           3
latency         110 ms
throughput      7.8 req/s
gpuUsage        81%
vramUsage       87%
```

A custom Router can decide which metrics matter for its own routing policy.

InfraMesh does not require every Router to use every available metric.

---

# Worker Selection

The Router selects exactly one Worker from the `WorkerCandidate` list supplied by Console.

For example:

```text
Request

preferredModel = qwen3
requiredProvider = OLLAMA


WorkerCandidates

Worker A
  provider = OLLAMA
  models = [qwen3, gemma3]
  currentRequestCount = 2

Worker B
  provider = VLLM
  models = [qwen3]
  currentRequestCount = 0

Worker C
  provider = OLLAMA
  models = [gemma3]
  currentRequestCount = 0

              │
              ▼

           Router

              │
              ▼

          Worker A
```

In this example:

```text
Worker B
→ rejected because requiredProvider does not match

Worker C
→ provider matches but preferredModel does not match

Worker A
→ satisfies both conditions
```

The exact selection algorithm is implementation-specific.

---

# Custom Routing

Router implementations are free to define their own routing algorithms.

For example:

```text
RoutingRequest
      │
      ▼
Custom Router
      │
      ├── Rule Engine
      │
      ├── Scoring Algorithm
      │
      ├── AI / LLM
      │
      ├── ML Model
      │
      ├── Cost Policy
      │
      └── Organization-specific Logic
      │
      ▼
Selected Worker
```

The Router contract does not require an AI model.

A completely deterministic Router is valid.

For example:

```text
Candidates
    │
    ▼
Filter by required provider
    │
    ▼
Filter by gpuRequired / privateData
    │
    ▼
Prefer requested model
    │
    ▼
Lowest queue
    │
    ▼
Lowest latency
    │
    ▼
Selected Worker
```

is a valid Router implementation.

---

# AI-Based Routing

AI-based routing is one possible Router implementation.

For example:

```text
User Request

"Spring AI가 뭐야?"

        │
        ▼

RoutingRequest
        │
        ├── Request Content
        └── WorkerCandidates
        │
        ▼

AI-based Router
        │
        ├── Analyze request intent
        ├── Analyze Worker capabilities
        ├── Analyze runtime state
        └── Select Worker
        │
        ▼

Coding-oriented Worker
```

An AI-based Router may consider:

```text
Request intent
Request complexity
Preferred model
Provider constraints
Tool requirements

Worker models
Worker provider

Current requests
Queue
Latency
Throughput
Error count

CPU
Memory
GPU
VRAM
```

However, AI usage is an implementation detail of the Router.

It is not part of the InfraMesh Router protocol itself.

---

# AI Router Naming

InfraMesh may refer to the routing strategy that delegates Worker selection to a Router node as:

```text
AI_ROUTER
```

`AI_ROUTER` does **not mean that the Router implementation must use an AI or LLM model**.

It means that Worker selection is delegated to an independently deployed Router node.

For example, all of the following are valid implementations behind the `AI_ROUTER` strategy:

```text
AI_ROUTER
   │
   ├── LLM-based Router
   ├── Rule-based Router
   ├── Score-based Router
   ├── Hardware-aware Router
   ├── Cost-aware Router
   └── Custom Organization Router
```

The Router implementation remains completely customizable.

---

# RoutingResponse

The Router returns the common `RoutingResponse` defined by `infra-node`.

In `infra-node` `0.1.1` it has exactly one field, the numeric id of the selected Worker:

```json
{ "workerId": 17 }
```

Router-specific details (scores, tiers, model output, confidence) are not part of the
response; they belong in the Router's own logs.

For example:

```text
RoutingRequest
      │
      ▼
Router
      │
      ▼
Select Worker 17
      │
      ▼
RoutingResponse
      │
      └── workerId = 17
```

The exact fields and constructor should always follow the current `infra-node` version.

Router implementations should not define incompatible local response DTOs.

---

# Worker Selection Validation

The Router must never return an arbitrary Worker ID.

After the routing algorithm chooses a Worker, the selected ID must be validated against the `WorkerCandidate` list contained in the request.

Conceptually:

```text
Routing Algorithm

workerId = 17

      │
      ▼

RoutingRequest.workers

[11, 17, 24]

      │
      ▼

17 exists

      │
      ▼

RoutingResponse
```

If the routing algorithm produces:

```text
workerId = 999
```

but the candidate list contains:

```text
[11, 17, 24]
```

the Router must treat the result as invalid.

It must not return an unknown Worker to Console.

Console should also validate the returned Worker ID against its original Worker candidate list.

Therefore validation occurs at both boundaries:

```text
Router Algorithm
      │
      ▼
Router Validation
      │
      ▼
RoutingResponse
      │
      ▼
Console Validation
      │
      ▼
Worker Invocation
```

---

# No Implicit Fallback

A Router implementation should not silently return:

```text
first Worker

random Worker

lowest-latency Worker
```

when its configured routing algorithm fails unless such fallback behavior is explicitly part of that Router's documented routing policy.

For the reference implementation, routing failures should be reported clearly rather than silently changing routing strategy.

Fallback behavior can be introduced later as an explicit Router policy.

---

# Router Does Not Call Workers

The Router must not perform the following flow:

```text
Console
   │
   ▼
Router
   │
   ▼
Worker
   │
   ▼
AI Runtime
```

Instead:

```text
Console
   │
   │ RoutingRequest
   ▼
Router
   │
   │ RoutingResponse
   ▼
Console
   │
   │ WorkerRequest
   ▼
Worker
   │
   ▼
AI Runtime
```

This keeps routing and inference execution independent.

It also allows Console to maintain control over:

```text
Worker authorization
Execution telemetry
Session affinity
Streaming
Error handling
Request lifecycle
```

without coupling those responsibilities to Router implementations.

---

# Router Does Not Discover Workers

The Router does not query Console databases or infrastructure repositories to discover Workers.

It should not depend on Console domain objects such as:

```text
Team
TeamNode
Node
Worker Entity
WorkerModel Entity
NodeRuntime Entity
```

It should also not depend on Console repositories such as:

```text
TeamNodeRepository
WorkerRepository
NodeRuntimeRepository
```

Instead:

```text
Console Domain
      │
      ▼
WorkerCandidate
      │
      ▼
RoutingRequest
      │
      ▼
Router
```

The Router only understands the common protocol defined by `infra-node`.

---

# Router Implementation

This repository provides four reference Router implementations — `router-spring-ai-example`
(AI-based), `router-static-example` (deterministic, metric-based), `router-jev-example`
(Jev typed-decision API), and `router-laya-example` (Laya semantic routing, Python / FastAPI).
See [Modules](#modules) for details on each.

When building a custom Router, use this project as a reference for:

- `infra-node` integration
- Router configuration
- Health endpoint implementation
- Route endpoint implementation
- `RoutingRequest` handling
- `RoutingHints` handling
- `WorkerCandidate` handling
- Worker selection
- Routing result validation
- Authentication
- Error handling

A custom implementation does not need to copy the routing algorithm used by this example.

The important requirement is to follow the **InfraMesh Router contract defined by `infra-node`**.

```text
                     infra-node
                         │
                  Router Contract
                         │
           ┌─────────────┴─────────────┐
           │                           │
           ▼                           ▼

Reference Router                 Custom Router

Rule / AI / Score               Any Algorithm

           │                           │
           └─────────────┬─────────────┘
                         │
                         ▼
                      InfraMesh
```

---

# Authentication

Routers authenticate DIRECT requests from InfraMesh Console with the node API key.

```http
X-Infra-Api-Key: <NODE_API_KEY>
```

The API key is issued and managed through Infra Console. In `infra-node` `0.1.1` this is the
whole mechanism, and it is always on:

- The SDK auto-configures a filter (`NodeApiKeyServletFilter` for servlet applications,
  `NodeApiKeyFilter` for reactive ones) on every path starting with `/api/v1` — the Router's
  own `POST /api/v1/route` as well as `GET /api/v1/health`. Other paths are not checked.
- The key is configured as `infra.node.api-key` and must be a UUID.
- A request whose `X-Infra-Api-Key` header is missing or does not equal the configured key
  exactly is rejected with `401` and an empty body.

```yaml
infra:
  node:
    api-key: ${INFRA_NODE_API_KEY}
```

There is no authentication mode setting and no SDK switch to disable the filter.

OUTBOUND connections do not use the API key: the node authenticates to Console with its
`nodeId` and `credential` during the WebSocket handshake (see [Connection Mode](#connection-mode)).

Custom Router implementations should reuse the authentication support provided by `infra-node` instead of defining incompatible authentication protocols.

---

# Connection Mode

A Router uses the same connection model and the same `inframesh.node.*` configuration as a Worker. Both modes coexist; OUTBOUND does not replace DIRECT.

```text
DIRECT (default)

Console ──HTTP──▶ Router          POST /api/v1/route


OUTBOUND

Console
   ▲
   │ Persistent WebSocket
   │
infra-node Connection SDK
   │
Router
```

Responsibilities are split between the two projects:

```text
infra-node    Common SDK + Connection Infrastructure
              (WebSocket client, authentication header, heartbeat, reconnect,
               backoff / jitter, lifecycle, graceful shutdown, NodeEnvelope transport)

infra-router  Router Reference Implementation + Routing Decision
              (Router REQUEST handler, RoutingRequest analysis, RoutingResponse)
```

A Router implementer does **not** write a WebSocket client, heartbeat, reconnect, authentication header, or lifecycle code — adding `infra-node` is enough. There is no Router-specific connection module.

OUTBOUND is opt-in. The connection is auto-configured by `infra-node` only when `connection-mode` is `OUTBOUND`; every example module ships it as an `outbound` Spring profile (`src/main/resources/application-outbound.yml`):

```yaml
inframesh:
  node:
    connection-mode: OUTBOUND
    console-url: ${INFRAMESH_CONSOLE_URL}
    node-id: ${INFRAMESH_NODE_ID}
    credential: ${INFRAMESH_NODE_CREDENTIAL}
```

```bash
INFRAMESH_CONSOLE_URL=https://console.example.com \
INFRAMESH_NODE_ID=<uuid> \
INFRAMESH_NODE_CREDENTIAL=<credential> \
./gradlew :router-static-example:bootRun --args='--spring.profiles.active=outbound'
```

Each example registers one Router REQUEST handler that reuses the **same** `RouterService` as DIRECT's `POST /api/v1/route` — only the transport differs:

```java
@Bean
public NodeRequestHandler<RoutingRequest, RoutingResponse> outboundRequestHandler(RouterService routerService) {
    return NodeRequestHandler.of(RoutingRequest.class, routerService::route);
}
```

```text
Console
   │ NodeEnvelope REQUEST (payload: RoutingRequest, requestId)
   ▼
Persistent WebSocket
   │
   ▼
infra-node Connection SDK
   │
   ▼
Router REQUEST Handler
   │
   ▼
RouterService.route        (existing routing decision)
   │
   ▼
infra-node Connection SDK
   │ NodeEnvelope RESPONSE (payload: RoutingResponse, same requestId) — or ERROR
   ▼
Console
```

> **Current status.** The Router side of this flow is implemented and tested: an OUTBOUND Router authenticates, connects, heartbeats and reconnects through `infra-node`, and answers a `RoutingRequest` REQUEST with a `RoutingResponse` RESPONSE. **Infra Console does not dispatch routing requests over the outbound connection yet** — it only selects DIRECT Routers for the `AI_ROUTER` strategy and calls them over HTTP. Until Console-side dispatch is added, run Routers that must take part in routing in DIRECT mode.

---

# Stateless Design

Router implementations should remain stateless whenever possible.

```text
                     Infra Console
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
           Router 1    Router 2    Router 3
              │           │           │
              └───────────┼───────────┘
                          │
                          ▼
                  Routing Decisions
```

A routing request should contain the information necessary to make the routing decision.

The Router should not require a local copy of Console infrastructure state.

This enables:

```text
Independent deployment
Horizontal scaling
Simple failover
Replaceable Router implementations
Stateless Router instances
```

Persistent infrastructure state remains managed by InfraMesh Console.

---

# Extensibility

Organizations are free to customize Router behavior.

Possible implementations include:

- AI-based Worker selection
- Rule-based routing
- Score-based routing
- Least-latency routing
- Least-busy routing
- Model-specific routing
- Provider-specific routing
- Hardware-aware routing
- GPU-aware routing
- VRAM-aware routing
- Cost-aware routing
- Tool-capability routing
- Organization-specific routing
- Hybrid routing strategies

For example:

```text
                    RoutingRequest
                          │
                          ▼
                     Custom Router
                          │
        ┌─────────────────┼─────────────────┐
        │                 │                 │
        ▼                 ▼                 ▼
     Rules              Scores             AI
        │                 │                 │
        └─────────────────┼─────────────────┘
                          │
                          ▼
                   Selected Worker
```

InfraMesh defines the communication contract.

The internal algorithm used to select a Worker remains implementation-specific.

---

# Getting Started

Start by adding the `infra-node` dependency to the Router project.

```gradle
dependencies {
    implementation 'io.github.inframeshai:inframesh-node:0.1.1'
}
```

Then use one of the example implementations in this repository as a reference — see
[Modules](#modules) above for `router-spring-ai-example` (AI-based), `router-static-example`
(deterministic, metric-based), `router-jev-example` (Jev typed-decision API), and
`router-laya-example` (Laya semantic routing — a Python service that implements the same
HTTP contract without the Java SDK).

At minimum, a Router should:

1. Integrate `infra-node`.
2. Provide the Router health endpoint.
3. Provide `POST /api/v1/route`.
4. Accept the latest `RoutingRequest`.
5. Read the provided `WorkerCandidate` list.
6. Process relevant `RoutingHints`.
7. Analyze request information when needed.
8. Select one Worker from the supplied candidates.
9. Validate that the selected Worker belongs to the supplied candidate list.
10. Return the result using `RoutingResponse`.

The Router does not need to:

```text
Discover Workers from Console

Connect to the Console database

Invoke Workers

Proxy inference responses

Implement routing streaming
```

---

# Example Routing Algorithm

A simple deterministic Router could implement the following algorithm:

```text
RoutingRequest
      │
      ▼
WorkerCandidates
      │
      ▼
Apply Required Constraints
      │
      ▼
Eligible Workers
      │
      ▼
Apply Preferred Model / Provider
      │
      ▼
Compare Runtime State
      │
      ├── Queue
      ├── Active Requests
      ├── Latency
      ├── Throughput
      ├── CPU
      ├── Memory
      ├── GPU
      └── VRAM
      │
      ▼
Select Worker
      │
      ▼
Validate workerId
      │
      ▼
RoutingResponse
```

An AI-based implementation could replace or supplement the scoring phase without changing the external Router protocol.

---

# Design Goals

Infra Router follows several core principles:

- **Routing First** — The Router decides where inference should execute.
- **No Inference Execution** — Final inference is always performed by Workers.
- **No Worker Invocation** — The Router returns a routing decision rather than invoking the Worker.
- **Protocol First** — Router implementations follow the `infra-node` contract.
- **Candidate Based** — Router selects only from Worker candidates provided by Console.
- **Implementation Neutral** — AI, rules, scoring, ML, or custom logic may be used.
- **Vendor Neutral** — Routing is not tied to a specific model or provider.
- **Extensible** — Organizations can implement their own routing logic.
- **Stateless** — Router instances should not depend on local persistent infrastructure state.
- **Independent Deployment** — Routers can be deployed separately from Console and Workers.
- **Horizontally Scalable** — Multiple Router instances can operate concurrently.

---

# Component Responsibilities

The responsibilities of each InfraMesh component should remain clearly separated.

```text
Console
────────────────────────────────
Infrastructure management
Organization / Team management
Node management
Worker health management
Worker availability filtering
Worker candidate generation
Router invocation
Routing result validation
Worker invocation
Streaming orchestration


Router
────────────────────────────────
RoutingRequest analysis
RoutingHints analysis
WorkerCandidate analysis
Custom routing algorithm
Worker selection
Selection validation
RoutingResponse generation


Worker
────────────────────────────────
Inference execution
Runtime integration
Model execution
Invoke handling
Streaming handling


infra-node
────────────────────────────────
Common SDK
Protocol DTOs
Router contracts
Worker contracts
Health contracts
Authentication integration
Node integration specifications
Outbound Connection SDK
  (WebSocket client, heartbeat, reconnect,
   lifecycle, NodeEnvelope transport)
```

This separation should remain consistent even for custom Router implementations.

---

# Related Projects

| Project | Description |
|---|---|
| `infra-console` | InfraMesh control plane, infrastructure management, routing orchestration, and Worker invocation |
| `infra-node` | Core SDK and common integration contracts for Router and Worker nodes |
| `infra-router` | Reference implementation for building custom Router nodes |

---

# Status

`infra-router` is currently under active development.

Router contracts, DTOs, configuration properties, and reference implementations may evolve as InfraMesh develops.

When the Router contract changes, the corresponding `infra-node` version should be treated as the source of truth.

---

# License

Apache License 2.0
