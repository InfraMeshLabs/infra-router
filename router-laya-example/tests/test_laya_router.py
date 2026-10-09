"""Laya adapter and HTTP contract tests.

Laya itself is always replaced by a fake engine: what the model judges for a given
prompt is not deterministic and is deliberately not asserted here.
"""

import pytest
from fastapi.testclient import TestClient

from app.config import FallbackPolicy, ModelCapabilityRule, Settings
from app.laya_router import QUESTIONS, WARM_UP_PROMPT, LayaRouter
from app.main import create_app
from app.models import ChatMessage, RoutingError, RoutingRequest, WorkerTier
from app.prompt_builder import PromptBuilder

API_KEY = "0e009b03-345e-4b23-9091-d3f9be20182d"


class FakeEngine:
    def __init__(self, choice="medium", error=None):
        self.choice = choice
        self.error = error
        self.prompts = []

    def predict(self, prompt, questions):
        assert questions is QUESTIONS
        self.prompts.append(prompt)
        if self.error and prompt != WARM_UP_PROMPT:
            raise self.error
        return {"answers": {"worker": {"type": "choice", "choice": self.choice,
                                       "probabilities": {"small": 0.1, "medium": 0.6, "powerful": 0.3}}}}


def warmed_up(engine) -> LayaRouter:
    laya = LayaRouter(engine_factory=lambda: engine)
    laya.warm_up()
    return laya


# --- LayaRouter ---------------------------------------------------------------------------


def test_creates_the_engine_once_and_becomes_ready_only_after_warm_up():
    created = []
    engine = FakeEngine()
    laya = LayaRouter(engine_factory=lambda: created.append(engine) or engine)

    assert not laya.created and not laya.ready

    laya.warm_up()
    laya.classify("first")
    laya.classify("second")

    assert laya.created and laya.ready
    assert len(created) == 1
    assert engine.prompts == [WARM_UP_PROMPT, "first", "second"]


def test_is_not_ready_when_warm_up_fails():
    class BrokenEngine:
        def predict(self, prompt, questions):
            raise RuntimeError("checkpoint download failed")

    laya = LayaRouter(engine_factory=BrokenEngine)

    with pytest.raises(RuntimeError):
        laya.warm_up()
    assert not laya.ready


@pytest.mark.parametrize("choice, tier", [
    ("small", WorkerTier.SMALL),
    ("medium", WorkerTier.MEDIUM),
    ("POWERFUL", WorkerTier.POWERFUL),
])
def test_converts_the_laya_choice_to_a_worker_tier(choice, tier):
    decision = warmed_up(FakeEngine(choice)).classify("anything")

    assert decision.tier is tier
    assert decision.probabilities == {"small": 0.1, "medium": 0.6, "powerful": 0.3}


def test_reads_an_object_shaped_result_too():
    class Answer:
        choice = "powerful"
        probabilities = None

    class Result:
        answers = {"worker": Answer()}

    class ObjectEngine:
        def predict(self, prompt, questions):
            return Result()

    assert warmed_up(ObjectEngine()).classify("anything").tier is WorkerTier.POWERFUL


@pytest.mark.parametrize("engine", [FakeEngine(choice="gigantic"), FakeEngine(choice=None),
                                    FakeEngine(error=RuntimeError("boom"))])
def test_reports_an_unusable_laya_answer_as_bad_gateway(engine):
    with pytest.raises(RoutingError) as error:
        warmed_up(engine).classify("anything")

    assert error.value.status == 502


def test_refuses_to_classify_before_warm_up():
    with pytest.raises(RoutingError) as error:
        LayaRouter(engine_factory=FakeEngine).classify("anything")

    assert error.value.status == 502


# --- PromptBuilder ------------------------------------------------------------------------


def test_prompt_is_the_last_user_message():
    request = RoutingRequest(messages=[
        ChatMessage(role="SYSTEM", content="You are helpful."),
        ChatMessage(role="USER", content="first question"),
        ChatMessage(role="ASSISTANT", content="an answer"),
        ChatMessage(role="USER", content="  second question  "),
        ChatMessage(role="ASSISTANT", content=None),
    ])

    assert PromptBuilder().build(request) == "second question"


def test_prompt_is_none_when_there_is_no_message_content():
    assert PromptBuilder().build(RoutingRequest(messages=[])) is None
    assert PromptBuilder().build(RoutingRequest(messages=[ChatMessage(role="USER", content=" ")])) is None


# --- HTTP contract ------------------------------------------------------------------------


def worker(worker_id, model):
    return {"workerId": worker_id, "name": f"worker-{worker_id}", "provider": "OLLAMA",
            "models": [{"provider": "OLLAMA", "modelName": model, "executionLocation": "LOCAL"}],
            "currentRequestCount": 0, "queueSize": 0, "averageLatency": 120.5,
            "someFutureField": "ignored"}


def routing_request(workers, routing=None):
    return {"sessionId": "session-1", "messages": [{"role": "USER", "content": "hello"}],
            "options": None, "routing": routing, "tools": [], "toolChoice": None, "workers": workers}


@pytest.fixture
def engine():
    return FakeEngine("medium")


@pytest.fixture
def client(engine):
    settings = Settings(api_key=API_KEY, fallback_policy=FallbackPolicy.STRICT, model_capabilities=(
        ModelCapabilityRule("gemma3:4b", WorkerTier.SMALL),
        ModelCapabilityRule("qwen3:8b", WorkerTier.MEDIUM),
    ))
    app = create_app(settings=settings, laya_router=LayaRouter(engine_factory=lambda: engine))
    with TestClient(app, headers={"X-Infra-Api-Key": API_KEY}) as test_client:
        yield test_client


def test_route_returns_only_the_selected_worker_id(client, engine):
    response = client.post("/api/v1/route", json=routing_request([worker(1, "gemma3:4b"), worker(2, "qwen3:8b")]))

    assert response.status_code == 200
    assert response.json() == {"workerId": 2}
    # Warm-up ran at startup, then Laya saw the message text - not the request JSON.
    assert engine.prompts == [WARM_UP_PROMPT, "hello"]


def test_route_rejects_no_worker_candidates_without_calling_laya(client, engine):
    response = client.post("/api/v1/route", json=routing_request([]))

    assert response.status_code == 422
    assert response.json()["status"] == 422
    assert response.json()["path"] == "/api/v1/route"
    assert engine.prompts == [WARM_UP_PROMPT]


def test_route_rejects_when_a_hard_routing_hint_leaves_no_worker(client, engine):
    response = client.post("/api/v1/route",
                           json=routing_request([worker(1, "qwen3:8b")], {"requiredProvider": "VLLM"}))

    assert response.status_code == 422
    assert engine.prompts == [WARM_UP_PROMPT]


def test_route_reports_a_laya_failure_as_bad_gateway(client, engine):
    engine.error = RuntimeError("boom")

    response = client.post("/api/v1/route", json=routing_request([worker(1, "qwen3:8b")]))

    assert response.status_code == 502


@pytest.mark.parametrize("headers", [{"X-Infra-Api-Key": ""}, {"X-Infra-Api-Key": "wrong"}])
def test_api_requires_the_node_api_key(client, headers):
    assert client.get("/api/v1/health", headers=headers).status_code == 401
    assert client.post("/api/v1/route", headers=headers, json=routing_request([])).status_code == 401


@pytest.mark.parametrize("api_key", [None, "", "  "])
def test_security_enabled_without_an_api_key_refuses_to_start(api_key):
    with pytest.raises(ValueError, match="security.enabled"):
        Settings(api_key=api_key)


def test_missing_api_key_in_the_environment_fails_startup_unless_security_is_explicitly_disabled(monkeypatch):
    monkeypatch.delenv("INFRA_NODE_API_KEY", raising=False)

    monkeypatch.delenv("SECURITY_ENABLED")
    with pytest.raises(ValueError, match="security.enabled"):
        Settings.load()

    monkeypatch.setenv("SECURITY_ENABLED", "false")
    assert Settings.load().security_enabled is False

    monkeypatch.setenv("SECURITY_ENABLED", "flase")
    with pytest.raises(ValueError):
        Settings.load()


def test_api_key_in_the_environment_enables_authentication_by_default(monkeypatch):
    monkeypatch.delenv("SECURITY_ENABLED")
    monkeypatch.setenv("INFRA_NODE_API_KEY", API_KEY)

    settings = Settings.load()

    assert settings.security_enabled is True and settings.api_key == API_KEY


def test_api_key_is_a_uuid_compared_in_canonical_form_like_infra_node():
    assert Settings(api_key=API_KEY.upper()).api_key == API_KEY
    with pytest.raises(ValueError, match="UUID"):
        Settings(api_key="not-a-uuid")


def test_null_collections_are_read_as_empty_lists_like_the_java_records():
    request = RoutingRequest.model_validate({
        "messages": [{"role": "ASSISTANT", "content": None, "toolCalls": None}],
        "options": {"temperature": 0.2, "maxTokens": 256, "topP": None},
        "routing": {"requiredCapabilities": None, "privateData": None},
        "tools": None,
        "toolChoice": {"mode": "TOOL", "toolName": "search"},
        "workers": [{"workerId": 7, "models": None}],
    })

    assert request.tools == [] and request.routing.required_capabilities == []
    assert request.messages[0].tool_calls == [] and request.workers[0].models == []
    assert request.options.max_tokens == 256 and request.tool_choice.tool_name == "search"
    assert RoutingRequest.model_validate({}).workers == []


def test_explicitly_disabled_security_accepts_requests_without_a_key():
    app = create_app(settings=Settings(security_enabled=False), laya_router=LayaRouter(engine_factory=FakeEngine))
    with TestClient(app) as unauthenticated:
        assert unauthenticated.get("/api/v1/health").status_code == 200


def test_health_follows_the_node_health_contract(client):
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"status", "system", "runtime"}
    assert body["status"] == "UP"
    assert set(body["system"]) == {"cpu", "memory", "gpus"}
    assert set(body["system"]["memory"]) == {"total", "used", "available"}
    assert body["runtime"] == {"activeRequests": 0}
