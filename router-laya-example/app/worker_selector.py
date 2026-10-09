"""Worker capability + runtime state: everything about picking a Worker that is not Laya's job."""

from __future__ import annotations

import logging
from fnmatch import fnmatchcase
from typing import Optional

from app.config import FallbackPolicy, ModelCapabilityRule, ScoreWeights, Settings
from app.models import RoutingError, RoutingHints, WorkerCandidate, WorkerTier

log = logging.getLogger(__name__)

UNPROCESSABLE_ENTITY = 422
LOCAL = "LOCAL"


class ModelCapabilityRegistry:
    """Maps a model name to the WorkerTier it can serve, from configuration - no model
    name is hardcoded here."""

    def __init__(self, rules: tuple[ModelCapabilityRule, ...], default_tier: WorkerTier) -> None:
        self._rules = tuple((rule.pattern.lower(), rule.tier) for rule in rules)
        self._default_tier = default_tier

    def tier_of_model(self, model_name: Optional[str]) -> WorkerTier:
        name = (model_name or "").lower()
        for pattern, tier in self._rules:
            if fnmatchcase(name, pattern):
                return tier
        return self._default_tier

    def tier_of_worker(self, worker: WorkerCandidate) -> WorkerTier:
        # A Worker is as capable as its most capable model.
        tiers = [self.tier_of_model(model.model_name) for model in worker.models or []]
        return max(tiers, key=lambda tier: tier.rank) if tiers else self._default_tier


class WorkerSelector:
    """Selects one Worker from the candidates Console supplied - never from anywhere else.

    RoutingHints are enforced exactly like the other Router examples: ``requiredProvider``,
    ``gpuRequired`` and ``privateData`` are hard filters (422 when nothing is left) and
    ``preferredModel`` is a soft preference.

    For the tier Laya asked for, the Workers of the closest tier that is at least as
    capable are used (a SMALL request does not occupy a POWERFUL Worker while a SMALL one
    exists). When no Worker is capable enough the FallbackPolicy decides. Within the chosen
    tier the Worker with the lowest Load Score wins - the same normalization, missing-metric
    handling and tie-break as router-static-example's ``StaticRouterServiceImpl``.
    """

    def __init__(self, settings: Settings) -> None:
        self._registry = ModelCapabilityRegistry(settings.model_capabilities, settings.default_tier)
        self._weights = settings.weights
        self._fallback_policy = settings.fallback_policy

    # --- RoutingHints filtering -----------------------------------------------------

    def filter_by_hints(
        self, candidates: Optional[list[WorkerCandidate]], hints: Optional[RoutingHints]
    ) -> list[WorkerCandidate]:
        if not candidates:
            raise RoutingError(UNPROCESSABLE_ENTITY, "No worker candidates were provided for routing.")
        if hints is None:
            return candidates

        eligible = candidates

        if hints.required_provider and hints.required_provider.strip():
            required = hints.required_provider.lower()
            eligible = [c for c in eligible if (c.provider or "").lower() == required]
            if not eligible:
                raise RoutingError(
                    UNPROCESSABLE_ENTITY,
                    f"No worker candidate matches the required provider: {hints.required_provider}",
                )

        if hints.gpu_required is True:
            eligible = [c for c in eligible if c.gpu and c.gpu.strip()]
            if not eligible:
                raise RoutingError(
                    UNPROCESSABLE_ENTITY,
                    "gpuRequired routing hint is set but no worker candidate reports a GPU.",
                )

        if hints.private_data is True:
            eligible = [c for c in eligible if self._is_safe_for_private_data(c)]
            if not eligible:
                raise RoutingError(
                    UNPROCESSABLE_ENTITY,
                    "privateData routing hint is set but no worker candidate is LOCAL-only.",
                )

        if hints.preferred_model and hints.preferred_model.strip():
            preferred = hints.preferred_model.lower()
            matched = [
                c for c in eligible if any((m.model_name or "").lower() == preferred for m in c.models or [])
            ]
            eligible = matched or eligible

        return eligible

    @staticmethod
    def _is_safe_for_private_data(candidate: WorkerCandidate) -> bool:
        # Fail closed: no models, or any model that is not known to be LOCAL, is unsafe.
        models = candidate.models or []
        return bool(models) and all(model.execution_location == LOCAL for model in models)

    # --- Tier + Load Score ----------------------------------------------------------

    def select(self, tier: WorkerTier, candidates: list[WorkerCandidate]) -> WorkerCandidate:
        if not candidates:
            raise RoutingError(UNPROCESSABLE_ENTITY, "No worker candidates were provided for routing.")

        by_tier: dict[WorkerTier, list[WorkerCandidate]] = {}
        for candidate in candidates:
            by_tier.setdefault(self._registry.tier_of_worker(candidate), []).append(candidate)

        capable = [t for t in by_tier if t.rank >= tier.rank]
        if capable:
            chosen_tier = min(capable, key=lambda t: t.rank)
        elif self._fallback_policy is FallbackPolicy.FALLBACK:
            chosen_tier = max(by_tier, key=lambda t: t.rank)
            log.warning(
                "No worker candidate satisfies tier %s; falling back to the highest available tier %s.",
                tier.value, chosen_tier.value,
            )
        else:
            raise RoutingError(
                UNPROCESSABLE_ENTITY,
                f"No worker candidate satisfies the required capability tier: {tier.value}",
            )

        return self._select_by_score(by_tier[chosen_tier])

    def _select_by_score(self, candidates: list[WorkerCandidate]) -> WorkerCandidate:
        max_request_count = _max_of(c.current_request_count for c in candidates)
        max_queue_size = _max_of(c.queue_size for c in candidates)
        max_latency = _max_of(c.average_latency for c in candidates)

        def sort_key(candidate: WorkerCandidate) -> tuple:
            # Deterministic tie-break: score, queueSize, currentRequestCount,
            # averageLatency, workerId - never random.
            return (
                self._score(candidate, max_request_count, max_queue_size, max_latency),
                _or_infinity(candidate.queue_size),
                _or_infinity(candidate.current_request_count),
                _or_infinity(candidate.average_latency),
                candidate.worker_id,
            )

        return min(candidates, key=sort_key)

    def _score(
        self, candidate: WorkerCandidate, max_request_count: float, max_queue_size: float, max_latency: float
    ) -> float:
        weights: ScoreWeights = self._weights
        # (normalized value, weight). Unbounded metrics are normalized relative to the
        # highest value in the candidate set; usage metrics are already 0-100 percentages.
        metrics = (
            (_ratio(candidate.current_request_count, max_request_count), weights.request_count),
            (_ratio(candidate.queue_size, max_queue_size), weights.queue_size),
            (_ratio(candidate.average_latency, max_latency), weights.latency),
            (_percentage(candidate.gpu_usage), weights.gpu_usage),
            (_percentage(candidate.vram_usage), weights.vram_usage),
        )

        # A missing metric is excluded, not defaulted to 0, and its weight is redistributed
        # over the metrics the Worker does report - otherwise a CPU-only Worker would look
        # better than a GPU Worker reporting low usage.
        weighted_sum = sum(value * weight for value, weight in metrics if value is not None)
        available_weight = sum(weight for value, weight in metrics if value is not None)
        return weighted_sum / available_weight if available_weight > 0 else 0.0


def _max_of(values) -> float:
    present = [value for value in values if value is not None]
    return float(max(present)) if present else 0.0


def _ratio(value: Optional[float], maximum: float) -> Optional[float]:
    if value is None:
        return None
    return 0.0 if maximum == 0 else float(value) / maximum


def _percentage(value: Optional[float]) -> Optional[float]:
    return None if value is None else float(value) / 100.0


def _or_infinity(value: Optional[float]) -> float:
    return float("inf") if value is None else float(value)
