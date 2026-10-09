import pytest

from app.config import FallbackPolicy, ModelCapabilityRule, Settings
from app.models import ModelInfo, RoutingError, RoutingHints, WorkerCandidate, WorkerTier
from app.worker_selector import WorkerSelector

SMALL_MODEL = "gemma3:4b"
MEDIUM_MODEL = "qwen3:8b"
POWERFUL_MODEL = "qwen3:32b"

RULES = (
    ModelCapabilityRule(SMALL_MODEL, WorkerTier.SMALL),
    ModelCapabilityRule(MEDIUM_MODEL, WorkerTier.MEDIUM),
    ModelCapabilityRule(POWERFUL_MODEL, WorkerTier.POWERFUL),
)


def selector(policy: FallbackPolicy = FallbackPolicy.FALLBACK, rules=RULES) -> WorkerSelector:
    return WorkerSelector(Settings(security_enabled=False, fallback_policy=policy, model_capabilities=rules))


def candidate(worker_id, model=SMALL_MODEL, provider="OLLAMA", request_count=0, queue_size=0, latency=0.0,
              gpu_usage=0.0, vram_usage=0.0, gpu="gpu", location="LOCAL"):
    return WorkerCandidate(
        worker_id=worker_id, name=f"worker-{worker_id}",
        models=[ModelInfo(provider=provider, model_name=model, execution_location=location)],
        provider=provider,
        current_request_count=request_count, queue_size=queue_size, average_latency=latency,
        gpu=gpu, gpu_usage=gpu_usage, vram=None if gpu is None else "vram", vram_usage=vram_usage,
    )


def one_of_each():
    return [candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL), candidate(3, POWERFUL_MODEL)]


@pytest.mark.parametrize("tier, expected", [
    (WorkerTier.SMALL, 1),
    (WorkerTier.MEDIUM, 2),
    (WorkerTier.POWERFUL, 3),
])
def test_selects_the_worker_of_the_requested_tier(tier, expected):
    assert selector().select(tier, one_of_each()).worker_id == expected


def test_picks_the_least_loaded_worker_among_the_same_tier():
    high = candidate(1, request_count=8, queue_size=5, latency=2400, gpu_usage=90, vram_usage=85)
    low = candidate(2, request_count=3, queue_size=1, latency=900, gpu_usage=50, vram_usage=60)
    medium = candidate(3, request_count=5, queue_size=0, latency=1200, gpu_usage=65, vram_usage=70)

    assert selector().select(WorkerTier.SMALL, [high, low, medium]).worker_id == 2


def test_tier_takes_precedence_over_load():
    busy_medium = candidate(1, MEDIUM_MODEL, request_count=9, queue_size=9, latency=5000)
    idle_small = candidate(2, SMALL_MODEL)

    assert selector().select(WorkerTier.MEDIUM, [busy_medium, idle_small]).worker_id == 1


def test_uses_the_closest_more_capable_tier_when_the_exact_tier_is_missing():
    workers = [candidate(1, SMALL_MODEL), candidate(2, POWERFUL_MODEL)]

    assert selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, workers).worker_id == 2


def test_small_request_does_not_take_a_powerful_worker_when_a_small_one_exists():
    workers = [candidate(1, POWERFUL_MODEL), candidate(2, SMALL_MODEL, request_count=5, queue_size=5)]

    assert selector().select(WorkerTier.SMALL, workers).worker_id == 2


def test_fallback_policy_uses_the_highest_available_tier_when_none_is_capable_enough():
    workers = [candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL)]

    assert selector(FallbackPolicy.FALLBACK).select(WorkerTier.POWERFUL, workers).worker_id == 2


def test_strict_policy_rejects_when_no_worker_is_capable_enough():
    workers = [candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL)]

    with pytest.raises(RoutingError) as error:
        selector(FallbackPolicy.STRICT).select(WorkerTier.POWERFUL, workers)

    assert error.value.status == 422


def test_worker_tier_is_its_most_capable_model_and_unknown_models_use_the_default_tier():
    multi_model = candidate(1, SMALL_MODEL)
    multi_model.models.append(ModelInfo(provider="OLLAMA", model_name="QWEN3:32B", execution_location="LOCAL"))
    unknown = candidate(2, "some-unlisted-model")

    assert selector(FallbackPolicy.STRICT).select(WorkerTier.POWERFUL, [multi_model, unknown]).worker_id == 1
    with pytest.raises(RoutingError):
        selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, [unknown])


def test_a_model_is_not_assigned_a_tier_just_because_it_shares_a_size_suffix():
    # Only the explicitly mapped model is MEDIUM; another ":8b" model stays at the default tier.
    unmapped_8b = candidate(1, "some-other-model:8b")

    with pytest.raises(RoutingError):
        selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, [unmapped_8b])


def test_capability_patterns_may_use_wildcards():
    rules = (ModelCapabilityRule("my-finetune-*", WorkerTier.POWERFUL),)
    workers = [candidate(1, SMALL_MODEL), candidate(2, "My-Finetune-v2")]

    assert selector(FallbackPolicy.STRICT, rules).select(WorkerTier.POWERFUL, workers).worker_id == 2


def test_does_not_favor_a_cpu_worker_just_because_it_has_no_gpu_metrics():
    cpu_only = candidate(1, request_count=5, queue_size=5, latency=2000, gpu=None, gpu_usage=None, vram_usage=None)
    healthy_gpu = candidate(2, request_count=1, queue_size=0, latency=200, gpu_usage=20, vram_usage=20)

    assert selector().select(WorkerTier.SMALL, [cpu_only, healthy_gpu]).worker_id == 2


def test_breaks_identical_scores_deterministically_by_worker_id():
    a = candidate(5, request_count=4, queue_size=2, latency=1000, gpu_usage=50, vram_usage=50)
    b = candidate(2, request_count=4, queue_size=2, latency=1000, gpu_usage=50, vram_usage=50)

    assert selector().select(WorkerTier.SMALL, [a, b]).worker_id == 2
    assert selector().select(WorkerTier.SMALL, [b, a]).worker_id == 2


# --- RoutingHints (same policy as the Java Router examples) -------------------------------


@pytest.mark.parametrize("workers", [None, []])
def test_rejects_requests_with_no_worker_candidates(workers):
    with pytest.raises(RoutingError) as error:
        selector().filter_by_hints(workers, None)

    assert error.value.status == 422


def test_filters_out_candidates_that_do_not_match_the_required_provider():
    ollama = candidate(1, provider="OLLAMA")
    vllm = candidate(2, provider="VLLM")

    eligible = selector().filter_by_hints([ollama, vllm], RoutingHints(required_provider="ollama"))

    assert [c.worker_id for c in eligible] == [1]


def test_rejects_when_required_provider_leaves_no_eligible_worker():
    with pytest.raises(RoutingError) as error:
        selector().filter_by_hints([candidate(1, provider="OLLAMA")], RoutingHints(required_provider="VLLM"))

    assert error.value.status == 422


def test_rejects_when_gpu_required_but_no_candidate_has_a_gpu():
    with pytest.raises(RoutingError) as error:
        selector().filter_by_hints([candidate(1, gpu=None)], RoutingHints(gpu_required=True))

    assert error.value.status == 422


def test_excludes_workers_that_are_not_local_only_when_private_data_is_required():
    external = candidate(1, location="EXTERNAL")
    unknown_location = candidate(2, location=None)
    local = candidate(3, location="LOCAL")

    eligible = selector().filter_by_hints([external, unknown_location, local], RoutingHints(private_data=True))

    assert [c.worker_id for c in eligible] == [3]


def test_rejects_when_private_data_leaves_no_eligible_worker():
    with pytest.raises(RoutingError) as error:
        selector().filter_by_hints([candidate(1, location="EXTERNAL")], RoutingHints(private_data=True))

    assert error.value.status == 422


def test_preferred_model_is_a_soft_preference():
    small = candidate(1, SMALL_MODEL)
    medium = candidate(2, MEDIUM_MODEL)

    narrowed = selector().filter_by_hints([small, medium], RoutingHints(preferred_model=MEDIUM_MODEL))
    unmatched = selector().filter_by_hints([small, medium], RoutingHints(preferred_model="not-offered"))

    assert [c.worker_id for c in narrowed] == [2]
    assert [c.worker_id for c in unmatched] == [1, 2]
