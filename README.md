# Infra Router

> The AI-native orchestration engine of InfraMesh.

Infra Router is responsible for analyzing incoming requests, building execution plans, and coordinating distributed Workers across the InfraMesh network.

Unlike traditional API gateways or load balancers, Infra Router does not simply forward requests. It reasons about the user's intent, determines how a request should be executed, and orchestrates one or more Workers to complete the task.

The Router does **not execute AI inference** itself. Instead, it builds execution plans and delegates execution to Workers.

---

# Overview

InfraMesh separates infrastructure management, orchestration, and execution into independent components.

```
                 +----------------------+
                 |    Infra Console     |
                 |    Control Plane     |
                 +----------+-----------+
                            |
                  Configuration & Metadata
                            |
                 +----------v-----------+
                 |    Infra Router      |
                 | AI Orchestrator      |
                 +----------+-----------+
                            |
                    Execution Planning
                            |
       +--------------------+--------------------+
       |                                         |
+------v------+                           +------v------+
|  Worker A   |                           |  Worker B   |
+-------------+                           +-------------+
```

Each component has a single responsibility.

- Console manages infrastructure.
- Router builds execution plans.
- Workers execute AI inference.

---

# Responsibilities

Infra Router is responsible for:

- Understanding user intent
- Building execution plans
- Discovering available Workers
- Selecting the most appropriate Worker
- Coordinating multiple Workers
- Orchestrating execution workflows
- Aggregating responses
- Recovering from Worker failures
- Streaming orchestration

The Router is the orchestration layer of InfraMesh.

---

# AI-Native Orchestration

The Router reasons about every incoming request before deciding how it should be executed.

Example:

```
User

"Spring AI가 뭐야?"
```

The Router may reason like this:

```
Programming question

↓

Knowledge request

↓

Coding group

↓

Search required?

↓

No

↓

Select best Coding Worker

↓

Execute
```

The execution plan is generated dynamically.

No routing rules need to be hardcoded.

---

# Execution Planning

Worker selection is only one part of the planning process.

A complete execution plan may look like this:

```
Incoming Request

↓

Intent Analysis

↓

Execution Planning

↓

Task A
↓

Worker A

Task B
↓

Worker B

Task C
↓

Worker C

↓

Merge Results

↓

Return Response
```

Simple requests may require only one Worker.

Complex requests may involve multiple Workers working together.

---

# Worker Discovery

Workers periodically publish their capabilities.

Example:

```json
[
  {
    "workerId": "worker-1",
    "groups": [
      "coding",
      "review"
    ],
    "models": [
      "gpt-5"
    ],
    "streaming": true,
    "priority": 100
  },
  {
    "workerId": "worker-2",
    "groups": [
      "translation"
    ],
    "models": [
      "gemini-2.5-pro"
    ],
    "streaming": true,
    "priority": 50
  }
]
```

The Router uses these capabilities when generating execution plans.

---

# Planning Model

Infra Router uses a configurable AI model to assist planning.

The planning model may determine:

- User intent
- Required Worker groups
- Execution order
- Parallel execution opportunities
- Model preferences
- Workflow complexity

Organizations are free to choose the planning model that best fits their needs.

---

# Multi-Worker Orchestration

Some requests require collaboration between multiple Workers.

Example:

```
User Request

        │

        ▼

Execution Plan

        │

 ┌──────┼────────┐
 ▼      ▼        ▼

Worker Worker Worker

 A       B       C

 └──────┼────────┘
        ▼

Response Aggregation

        ▼

Return
```

The Router coordinates the entire workflow.

---

# Extensibility

Infra Router is fully extensible.

Organizations may:

- Use the default Router implementation
- Configure different planning models
- Implement custom planning strategies
- Extend execution planning
- Replace orchestration logic entirely

InfraMesh defines the communication protocol.

How execution plans are generated is entirely implementation-specific.

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

Intent Analysis

        │

        ▼

Execution Planning

        │

        ▼

Worker Discovery

        │

        ▼

Worker Selection

        │

        ▼

Workflow Orchestration

        │

        ▼

Response Aggregation

        │

        ▼

Streaming Response
```

Each stage can be customized or replaced.

---

# Design Goals

Infra Router follows several core principles.

- AI-Native
- Orchestration First
- Execution Planning
- Protocol First
- Vendor Neutral
- Extensible
- Stateless
- Independent Deployment
- Horizontally Scalable
- Streaming First

---

# Future Features

Planned capabilities include:

- Planner optimization
- Multi-agent orchestration
- Cost-aware execution planning
- Distributed execution graphs
- Dynamic Worker learning
- Routing plugins
- Workflow templates
- Observability integration
- Metrics and tracing

---

# Related Projects

| Project | Description |
|----------|-------------|
| infra-console | Control Plane |
| infra-router | AI Orchestration Engine |
| infra-worker | AI Execution Runtime |

---

# License

Apache License 2.0
