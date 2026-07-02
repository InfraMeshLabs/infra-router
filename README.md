# Infra Router

> The AI-native decision engine of InfraMesh.

Infra Router is an intelligent orchestration engine that determines **how AI requests should be executed** across the InfraMesh network.

Unlike traditional API gateways or load balancers, Infra Router can leverage AI reasoning to analyze incoming requests, select the most appropriate execution strategy, and coordinate one or more Workers.

The Router itself does **not execute inference**. Instead, it decides **who should execute the request, how it should be executed, and in what order.**

---

# Overview

InfraMesh separates management, orchestration, and execution into independent components.

```
                 +----------------------+
                 |    Infra Console     |
                 |    Control Plane     |
                 +----------+-----------+
                            |
                    Configuration
                            |
                 +----------v-----------+
                 |    Infra Router      |
                 | AI Decision Engine   |
                 +----------+-----------+
                            |
          Reasoning & Execution Planning
                            |
       +--------------------+--------------------+
       |                                         |
+------v------+                           +------v------+
|  Worker A   |                           |  Worker B   |
+-------------+                           +-------------+
```

The Console manages infrastructure.

The Router makes decisions.

Workers execute inference.

---

# Responsibilities

Infra Router is responsible for:

- Understanding incoming requests
- Selecting the most appropriate Worker
- Choosing execution strategies
- Coordinating multiple Workers
- AI-assisted routing
- Streaming orchestration
- Session orchestration
- Failure recovery
- Request aggregation
- Workflow execution

The Router is the intelligence layer of InfraMesh.

---

# AI-Native Routing

Unlike traditional routers, Infra Router can use AI to determine where requests should go.

Example:

```
User

"Review this Spring Boot code."
```

The Router reasons:

```
This is a coding request.

↓

Use Coding Worker.

↓

GPT-5 is preferred.

↓

Worker #3 has the lowest load.

↓

Forward request.
```

The decision is not hardcoded.

It is generated dynamically.

---

# Execution Planning

A request is not always executed by a single Worker.

Example:

```
User

↓

Router

↓

Reasoning

↓

Worker A
(Code Analysis)

↓

Worker B
(Security Review)

↓

Worker C
(Summary)

↓

Merged Response
```

The Router can build execution pipelines depending on the request.

---

# Router Intelligence

The Router may consider:

- Request intent
- User-defined groups
- Worker capabilities
- Supported AI models
- Current workload
- Worker health
- Execution cost
- Latency
- Custom routing policies

Routing decisions are not limited to simple load balancing.

---

# Worker Discovery

Workers advertise their capabilities.

Example:

```json
[
  {
    "workerId": "worker-1",
    "group": "coding",
    "models": [
      "gpt-5"
    ]
  },
  {
    "workerId": "worker-2",
    "group": "translation",
    "models": [
      "gemini-2.5-pro"
    ]
  }
]
```

The Router selects Workers based on capabilities rather than static configuration.

---

# AI-assisted Routing

Infra Router can use an AI model to assist routing decisions.

Example:

```
Incoming Request

↓

Router AI

↓

Determine task type

↓

coding

↓

Determine best Worker

↓

Worker #3

↓

Execute
```

Organizations may:

- Use the default Router AI
- Configure their own AI model
- Build a custom Router implementation

---

# Multi-Worker Orchestration

Some requests require multiple Workers.

```
User Request

        │

        ▼

AI Router

        │

 ┌──────┼────────┐
 ▼      ▼        ▼

Worker Worker Worker

 A       B       C

 └──────┼────────┘
        ▼

Merged Response
```

The Router coordinates the entire workflow.

---

# Extensibility

Infra Router is designed to be extensible.

Users may:

- Replace the routing engine
- Use a different AI model
- Add custom routing policies
- Implement organization-specific logic
- Create entirely custom Routers

InfraMesh defines the communication protocol—not the routing implementation.

---

# Authentication

Routers authenticate with Infra Console using API Keys.

Incoming client requests are also authenticated using API Keys issued by the Console.

Supported authentication modes:

- API_KEY
- NONE (trusted network)

---

# Routing Lifecycle

```
Incoming Request

        │

        ▼

Authentication

        │

        ▼

AI Reasoning

        │

        ▼

Execution Planning

        │

        ▼

Worker Selection

        │

        ▼

Inference Execution

        │

        ▼

Response Aggregation

        │

        ▼

Streaming Response
```

Every stage can be customized.

---

# Design Goals

Infra Router follows several core principles.

- AI-Native
- Decision Driven
- Protocol First
- Vendor Neutral
- Extensible
- Stateless
- Horizontally Scalable
- Independent Deployment
- Multi-Worker Orchestration
- Streaming First

---

# Future Features

Planned capabilities include:

- Planner-based execution
- Multi-agent orchestration
- AI-powered routing optimization
- Cost-aware planning
- Model fallback strategies
- Distributed execution graphs
- Routing plugins
- Workflow templates
- Observability integration
- Metrics and tracing

---

# Related Projects

| Project | Description |
|----------|-------------|
| infra-console | Control Plane |
| infra-router | AI Decision Engine |
| infra-worker | AI Execution Runtime |

---

# License

Apache License 2.0
