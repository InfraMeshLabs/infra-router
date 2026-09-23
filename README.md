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
- **infra-node** defines the common SDK, DTOs, health contracts, authentication integration, and node specifications.

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

During local development, `infra-node` may be included as a JAR dependency.

Example:

```gradle
dependencies {
    implementation files('libs/infra-node-1.0.0.jar')
}
```

When repository-based distribution becomes available, this can be replaced with the corresponding repository dependency.

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
├── Health
│
└── Invoke
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
```

The health implementation should use the common health contract provided by `infra-node`.

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

# Invoke

Routing is performed through an invoke-based request.

```http
POST /api/v1/invoke
```

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

All routing decisions are completed through this single invoke flow.

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
          │ POST /api/v1/invoke
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

The exact fields are defined by the version of `infra-node` used by the Router.

A `RoutingRequest` conceptually contains:

```text
RoutingRequest
├── Request information
│   ├── Messages
│   ├── Chat options
│   ├── Tool information
│   └── Other supported request metadata
│
├── RoutingHints
│
└── WorkerCandidates
```

The Router should use the actual `RoutingRequest` record provided by the current `infra-node` dependency rather than redefining it locally.

Request information should remain available to the routing implementation so that custom Routers can make decisions based on the characteristics of the request.

For example, a Router may consider:

```text
Request content
Requested model
Provider constraints
Tool requirements
Request options
Routing hints
Worker capabilities
Worker runtime state
```

The exact routing algorithm remains implementation-specific.

---

# RoutingHints

`RoutingHints` represents routing preferences or constraints supplied with an inference request.

The exact contract is defined by `infra-node`.

Typical routing hints may include concepts such as:

```text
preferredModel
requiredModel

preferredProvider
requiredProvider

requiredCapabilities
```

depending on the current `infra-node` version.

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

`WorkerCandidate` can expose Worker capability and runtime information required for routing decisions.

Depending on the current `infra-node` contract, this may include information such as:

```text
Framework
Framework version

Uptime

Current request count
Queue size

Average latency
Throughput
Error count

CPU
CPU utilization

Memory
Memory utilization

GPU
GPU utilization

VRAM
VRAM utilization

Supported models
Provider information
```

This allows Router implementations to make decisions using both static capabilities and dynamic runtime state.

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
  activeRequests = 2

Worker B
  provider = VLLM
  models = [qwen3]
  activeRequests = 0

Worker C
  provider = OLLAMA
  models = [gemma3]
  activeRequests = 0

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
Filter by required capabilities
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
Requested model
Provider constraints
Tool requirements

Worker models
Worker capabilities

Current requests
Queue
Latency
Throughput
Error rate

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

Conceptually, the most important result is:

```text
selected workerId
```

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

RoutingRequest.workerCandidates

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

This repository provides a reference Router implementation.

When building a custom Router, use this project as a reference for:

- `infra-node` integration
- Router configuration
- Health endpoint implementation
- Invoke endpoint implementation
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

Routers can authenticate requests from InfraMesh Console using node API keys.

Example:

```http
X-Infra-Api-Key: <NODE_API_KEY>
```

The API key is issued and managed through Infra Console.

Depending on the deployment environment, authentication modes may include:

```text
API_KEY
NONE
```

`NONE` should only be used in trusted environments.

Custom Router implementations should reuse the authentication support provided by `infra-node` when available instead of defining incompatible authentication protocols.

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
    implementation files('libs/infra-node-1.0.0.jar')
}
```

Then use the example implementation in this repository as a reference.

At minimum, a Router should:

1. Integrate `infra-node`.
2. Provide the Router health endpoint.
3. Provide `POST /api/v1/invoke`.
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
