# Infra Router

> The intelligent routing layer of InfraMesh.

Infra Router is the data plane component responsible for receiving AI requests, selecting the most appropriate model and Worker, and forwarding inference requests across the InfraMesh network.

Unlike the Console, the Router does not manage infrastructure. It focuses entirely on intelligent request routing, load balancing, model resolution, and streaming responses.

---

# Overview

InfraMesh separates infrastructure management from request execution.

```
                 +----------------------+
                 |    Infra Console     |
                 |    Control Plane     |
                 +----------+-----------+
                            |
                Configuration / API Keys
                            |
        +-------------------+-------------------+
        |                                       |
+-------v--------+                     +--------v--------+
|  Infra Router  |                     |  Infra Router   |
+-------+--------+                     +--------+--------+
        |                                        |
        |                                        |
        +------------+---------------+-----------+
                     |               |
              +------v------+ +------v------+
              |   Worker 1  | |   Worker 2  |
              +-------------+ +-------------+
```

The Router is stateless and can be horizontally scaled.

---

# Router Extensibility

Infra Router is **not an AI server**.

It does not bundle or depend on any specific AI provider by default.

Instead, the Router provides a routing engine that can be extended by users to support any AI service.

This design allows organizations to:

- Integrate proprietary AI platforms
- Connect internal LLM services
- Support commercial AI providers
- Build custom routing logic
- Replace routing behavior without modifying InfraMesh itself

```
                 Infra Router
                        │
        ┌───────────────┼────────────────┐
        │               │                │
        ▼               ▼                ▼
   OpenAI Plugin   Ollama Plugin   Custom Plugin
        │               │                │
        ▼               ▼                ▼
     OpenAI         Ollama API      Internal AI
```

The default Router acts as a generic routing engine.

Users may:

- Use the default Router implementation.
- Add AI provider plugins.
- Develop completely custom Router implementations.
- Deploy multiple Router instances with different capabilities.

InfraMesh only defines the communication protocol.

How routing is implemented is entirely up to the Router implementation.

This enables organizations to build AI infrastructures without being locked into any specific AI vendor.

---

# Responsibilities

The Router is responsible for:

- Receiving AI requests
- Authenticating incoming API Keys
- Resolving AI Providers and Models
- Selecting the optimal Worker
- Forwarding inference requests
- Streaming responses
- Retry and failover
- Load balancing
- Session routing
- Health-aware routing

The Router never stores business configuration permanently.

---

# Core Concepts

## Request Routing

Every incoming request follows the same lifecycle.

```
Client
   │
   ▼
Authentication
   │
   ▼
Resolve Organization
   │
   ▼
Resolve Model
   │
   ▼
Select Worker
   │
   ▼
Forward Request
   │
   ▼
Return Response
```

---

## Model Resolution

Applications request a model.

Example:

```json
{
  "model": "gpt-5",
  "messages": [
    {
      "role": "user",
      "content": "Hello"
    }
  ]
}
```

The Router resolves:

```
gpt-5
    │
    ▼
OpenAI Provider
    │
    ▼
Available Workers
```

The application does not need to know which Worker executes the request.

---

## Worker Selection

The Router chooses the best available Worker.

Selection may consider:

- Worker availability
- Supported providers
- Supported models
- Current load
- Queue size
- GPU availability
- Region
- Latency
- User-defined routing policies

Selection strategies are pluggable.

Examples:

- Round Robin
- Least Connections
- Lowest Latency
- Priority
- Weighted
- Custom Strategy

---

## Session Routing

Some AI conversations require sticky sessions.

Example:

```
Conversation
      │
      ▼
Worker A
      │
      ▼
Continue Conversation
      │
      ▼
Worker A
```

If session affinity is enabled, the Router keeps requests on the same Worker whenever possible.

---

## Failover

If a Worker becomes unavailable:

```
Worker A
   │
Unavailable
   │
   ▼
Worker B
```

The Router automatically retries using another compatible Worker.

Retry policies are configurable.

---

## Streaming

The Router supports both request modes.

### Blocking

```
Client
    │
    ▼
Router
    │
    ▼
Worker
    │
    ▼
Complete Response
```

### Streaming

```
Token
Token
Token
Token
Token
```

Streaming responses are forwarded directly without buffering the entire response.

---

# AI Providers

The Router supports any provider exposed by Workers.

Examples:

- OpenAI
- Anthropic Claude
- Google Gemini
- Ollama
- Azure OpenAI
- OpenRouter
- Local LLMs
- Custom Providers

The Router itself is provider-neutral.

---

# Authentication

Incoming requests are authenticated using API Keys issued by Infra Console.

Supported authentication modes:

- API_KEY
- NONE (trusted network)

Worker communication is also authenticated.

---

# Architecture

```
                    Client
                      │
                      ▼
              +---------------+
              | Infra Router  |
              +-------+-------+
                      │
        +-------------+-------------+
        │                           │
        ▼                           ▼
+---------------+          +---------------+
| Worker Node A |          | Worker Node B |
+---------------+          +---------------+
```

The Router does not execute inference.

Its only responsibility is intelligent routing.

---

# Routing Pipeline

```
Incoming Request
        │
        ▼
Authentication
        │
        ▼
Organization Resolution
        │
        ▼
Model Resolution
        │
        ▼
Routing Strategy
        │
        ▼
Worker Selection
        │
        ▼
Inference Request
        │
        ▼
Streaming Response
```

Each stage is designed to be extensible.

---

# Design Goals

Infra Router is designed with the following principles:

- Stateless
- Horizontally scalable
- High availability
- Provider agnostic
- Worker agnostic
- Streaming first
- Low latency
- Pluggable routing
- Independent deployment

---

# Future Features

Planned capabilities include:

- Intelligent model fallback
- Multi-region routing
- Cost-aware routing
- GPU-aware scheduling
- Request prioritization
- Rate limiting
- Distributed session management
- Routing plugins
- Observability integration
- Metrics and tracing

---

# Related Projects

| Project | Description |
|----------|-------------|
| infra-console | Control Plane |
| infra-router | AI Request Router |
| infra-worker | AI Worker Runtime |
| infra-java | Java SDK |
| infra-cli | Command Line Interface |

---

# License

Apache License 2.0
