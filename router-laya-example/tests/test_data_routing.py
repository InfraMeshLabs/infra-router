"""DataRoutingPolicy + privateData, over the HTTP contract - the same cases as
router-laya-java-example's DataRoutingPolicyTest.

They only restrict the candidates; Laya's tier and the Worker selection then run unchanged.
In every test the EXTERNAL Worker is the less loaded one, so it is picked whenever it is allowed.
"""

import logging

import pytest
from fastapi.testclient import TestClient

from app.config import FallbackPolicy, ModelCapabilityRule, Settings
from app.data_routing import SensitivityVerdict
from app.laya_router import WARM_UP_PROMPT, LayaRouter
from app.main import create_app
from app.models import WorkerTier
from tests.test_laya_router import API_KEY, FakeEngine

SECRET = "my api key is sk-test-1234"
POLICIES = ["AUTO", "INTERNAL_ONLY", "EXTERNAL_ALLOWED"]


def worker(worker_id, model="qwen3:8b", location="LOCAL", request_count=0, provider="OLLAMA", gpu=None):
    return {"workerId": worker_id, "name": f"worker-{worker_id}", "provider": provider,
            "models": [{"provider": provider, "modelName": model, "executionLocation": location}],
            "currentRequestCount": request_count, "queueSize": 0, "gpu": gpu}


LOCAL = worker(1, location="LOCAL", request_count=10)
EXTERNAL = worker(2, location="EXTERNAL", request_count=0)


def request(workers, policy=None, routing=None, **extra):
    body = {"sessionId": "session-1", "messages": [{"role": "USER", "content": SECRET}],
            "routing": routing, "workers": workers, **extra}
    if policy is not None:
        body["dataRoutingPolicy"] = policy
    return body


class Checker:
    """Counts calls and returns a fixed verdict, or raises."""

    def __init__(self, verdict=SensitivityVerdict.UNKNOWN, error=None):
        self.verdict = verdict
        self.error = error
        self.calls = 0

    def __call__(self, _request):
        self.calls += 1
        if self.error:
            raise self.error
        return self.verdict


def client_for(checker=None, engine=None, fallback=FallbackPolicy.STRICT):
    settings = Settings(api_key=API_KEY, fallback_policy=fallback, model_capabilities=(
        ModelCapabilityRule("gemma3:4b", WorkerTier.SMALL),
        ModelCapabilityRule("qwen3:8b", WorkerTier.MEDIUM),
        ModelCapabilityRule("qwen3:32b", WorkerTier.POWERFUL),
        ModelCapabilityRule("gpt-4o", WorkerTier.MEDIUM),
    ))
    engine = engine or FakeEngine("medium")
    app = create_app(settings=settings, laya_router=LayaRouter(engine_factory=lambda: engine),
                     sensitivity_checker=checker)
    return TestClient(app, headers={"X-Infra-Api-Key": API_KEY})


def route(body, checker=None, **kwargs):
    with client_for(checker, **kwargs) as client:
        return client.post("/api/v1/route", json=body)


def assert_refused(response, code):
    assert response.status_code == 422
    assert response.json()["code"] == code
    assert SECRET not in response.json()["message"]


# --- AUTO -----------------------------------------------------------------------------------


def test_auto_keeps_a_sensitive_request_on_local_workers():
    assert route(request([LOCAL, EXTERNAL], "AUTO"), Checker(SensitivityVerdict.SENSITIVE)).json() == {"workerId": 1}


def test_auto_allows_external_workers_for_a_request_judged_not_sensitive():
    assert route(request([LOCAL, EXTERNAL], "AUTO"), Checker(SensitivityVerdict.NOT_SENSITIVE)).json() == {"workerId": 2}


@pytest.mark.parametrize("verdict", [SensitivityVerdict.UNKNOWN, None, "not-a-verdict"])
def test_auto_fails_closed_when_the_sensitivity_check_is_uncertain_or_gives_no_verdict(verdict):
    assert route(request([LOCAL, EXTERNAL], "AUTO"), Checker(verdict)).json() == {"workerId": 1}


def test_auto_fails_closed_when_the_sensitivity_check_fails_and_never_logs_the_content(caplog):
    checker = Checker(error=TimeoutError(f"timed out judging: {SECRET}"))

    with caplog.at_level(logging.DEBUG):
        response = route(request([LOCAL, EXTERNAL], "AUTO"), checker)

    assert response.json() == {"workerId": 1}
    assert "event=sensitivity_check_failed" in caplog.text
    assert SECRET not in caplog.text


def test_auto_without_a_configured_checker_uses_local_workers_only():
    assert route(request([LOCAL, EXTERNAL], "AUTO")).json() == {"workerId": 1}
    assert_refused(route(request([EXTERNAL], "AUTO")), "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER")


def test_auto_refuses_without_falling_back_to_external_workers_when_no_local_worker_is_left():
    engine = FakeEngine("medium")
    assert_refused(route(request([EXTERNAL], "AUTO"), Checker(SensitivityVerdict.SENSITIVE), engine=engine),
                   "SENSITIVE_DATA_NO_LOCAL_WORKER")
    assert_refused(route(request([EXTERNAL], "AUTO"), Checker(SensitivityVerdict.UNKNOWN), engine=engine),
                   "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER")
    assert engine.prompts == [WARM_UP_PROMPT, WARM_UP_PROMPT]


# --- INTERNAL_ONLY / EXTERNAL_ALLOWED --------------------------------------------------------


def test_internal_only_uses_only_local_workers_without_judging_the_content():
    checker = Checker(SensitivityVerdict.NOT_SENSITIVE)

    assert route(request([LOCAL, EXTERNAL], "INTERNAL_ONLY"), checker).json() == {"workerId": 1}
    assert checker.calls == 0


def test_internal_only_refuses_when_no_local_worker_is_left():
    engine = FakeEngine("medium")

    assert_refused(route(request([EXTERNAL], "INTERNAL_ONLY"), engine=engine), "INTERNAL_ONLY_NO_LOCAL_WORKER")
    assert engine.prompts == [WARM_UP_PROMPT]


def test_external_allowed_uses_local_and_external_workers_without_judging_the_content():
    checker = Checker(SensitivityVerdict.SENSITIVE)

    assert route(request([LOCAL, EXTERNAL], "EXTERNAL_ALLOWED"), checker).json() == {"workerId": 2}
    assert route(request([LOCAL], "EXTERNAL_ALLOWED"), checker).json() == {"workerId": 1}
    assert checker.calls == 0


# --- privateData wins under every policy -----------------------------------------------------


@pytest.mark.parametrize("policy", POLICIES)
def test_private_data_keeps_the_request_on_local_workers_under_every_policy(policy):
    checker = Checker(SensitivityVerdict.NOT_SENSITIVE)
    private = {"privateData": True}

    assert route(request([LOCAL, EXTERNAL], policy, private), checker).json() == {"workerId": 1}
    assert_refused(route(request([EXTERNAL], policy, private), checker), "PRIVATE_DATA_NO_LOCAL_WORKER")
    assert checker.calls == 0


@pytest.mark.parametrize("policy", POLICIES)
def test_private_data_false_leaves_the_policy_in_charge(policy):
    expected = 2 if policy == "EXTERNAL_ALLOWED" else 1

    assert route(request([LOCAL, EXTERNAL], policy, {"privateData": False})).json() == {"workerId": expected}


# --- The rest of the selection runs on the restricted candidates -----------------------------


def test_laya_tier_and_load_score_choose_among_the_local_workers_only():
    workers = [worker(1, "gemma3:4b", request_count=0), worker(2, request_count=10), worker(3, request_count=5),
               worker(4, location="EXTERNAL", request_count=0)]

    assert route(request(workers, "INTERNAL_ONLY")).json() == {"workerId": 3}


def test_tier_fallback_never_brings_back_an_excluded_external_worker():
    workers = [worker(1, "gemma3:4b"), worker(2, "qwen3:32b", location="EXTERNAL")]

    response = route(request(workers, "INTERNAL_ONLY"), engine=FakeEngine("powerful"), fallback=FallbackPolicy.FALLBACK)

    assert response.json() == {"workerId": 1}


def test_preferred_model_never_brings_back_an_excluded_external_worker():
    workers = [LOCAL, worker(2, "gpt-4o", location="EXTERNAL")]

    assert route(request(workers, "INTERNAL_ONLY", {"preferredModel": "gpt-4o"})).json() == {"workerId": 1}


def test_a_required_provider_or_gpu_only_external_workers_have_is_refused_not_sent_out():
    workers = [worker(1), worker(2, location="EXTERNAL", provider="OPENAI", gpu="H100")]

    assert_refused(route(request(workers, "INTERNAL_ONLY", {"requiredProvider": "OPENAI"})),
                   "INTERNAL_ONLY_NO_LOCAL_WORKER")
    assert_refused(route(request(workers, "AUTO", {"gpuRequired": True})), "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER")


# --- Compatibility ---------------------------------------------------------------------------


def test_a_request_without_data_routing_policy_is_auto():
    body = request([LOCAL, EXTERNAL])
    assert "dataRoutingPolicy" not in body

    assert route(body, Checker(SensitivityVerdict.NOT_SENSITIVE)).json() == {"workerId": 2}
    assert route(body, Checker(SensitivityVerdict.UNKNOWN)).json() == {"workerId": 1}


def test_a_null_data_routing_policy_is_auto():
    assert route(request([LOCAL, EXTERNAL], dataRoutingPolicy=None)).json() == {"workerId": 1}


@pytest.mark.parametrize("policy", ["SOMETIMES", "auto"])
def test_rejects_an_unknown_data_routing_policy_as_bad_request(policy):
    engine = FakeEngine("medium")

    response = route(request([LOCAL], policy), engine=engine)

    assert response.status_code == 400
    assert response.json()["message"] == (
        "Malformed RoutingRequest: dataRoutingPolicy must be one of [AUTO, INTERNAL_ONLY, EXTERNAL_ALLOWED].")
    assert engine.prompts == [WARM_UP_PROMPT]
