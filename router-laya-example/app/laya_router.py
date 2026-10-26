"""Laya adapter: owns the single Laya ``Router`` instance and turns its answer into a WorkerTier."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from typing import Any, Callable, Optional

from app.models import RoutingError, WorkerTier

log = logging.getLogger(__name__)

BAD_GATEWAY = 502
WARM_UP_PROMPT = "Hello"
QUESTION_KEY = "worker"

QUESTIONS = {
    QUESTION_KEY: {
        "type": "choice",
        "instructions": "Which worker class is most appropriate for this request?",
        "criteria": {
            WorkerTier.SMALL.value: "Simple questions, short conversations, basic tasks",
            WorkerTier.MEDIUM.value: "Moderate reasoning, coding, or analysis",
            WorkerTier.POWERFUL.value: "Complex reasoning, architecture, difficult coding tasks",
        },
    }
}


@dataclass(frozen=True)
class TierDecision:
    # The tier Laya chose - the most probable one.
    tier: WorkerTier
    # Laya's probability per tier label. The checkpoint ships uncalibrated temperatures, so
    # these rank the tiers but are not true likelihoods; tune router.laya.confidence-threshold
    # against real traffic.
    probabilities: Optional[dict[str, float]] = None

    def required_tier(self, confidence_threshold: Optional[float]) -> WorkerTier:
        """The tier to route to. Without a threshold, Laya's choice. With one, the lowest
        tier whose cumulative probability - it and every tier below it - reaches the
        threshold, but never below Laya's choice: an ambiguous answer escalates, never down."""
        if confidence_threshold is None or not self.probabilities:
            return self.tier
        cumulative = 0.0
        for candidate in WorkerTier:
            cumulative += self.probabilities.get(candidate.value, 0.0)
            if cumulative >= confidence_threshold:
                return candidate if candidate.rank >= self.tier.rank else self.tier
        # Rounding left the total just under the threshold: only the most capable tier is safe.
        return max(WorkerTier, key=lambda t: t.rank)


def _create_laya_engine() -> Any:
    # Imported lazily so tests (which inject a fake engine) do not need Laya installed.
    from laya import Router

    return Router()


def _get(container: Any, key: str) -> Any:
    if container is None:
        return None
    if isinstance(container, dict):
        return container.get(key)
    return getattr(container, key, None)


class LayaRouter:
    """Process-wide Laya adapter. Create it once at startup - never per request.

    Laya's ``predict()`` has not been verified as thread-safe, so calls are serialized
    with a lock rather than allowed to run concurrently on the one model instance.
    """

    def __init__(self, engine_factory: Callable[[], Any] = _create_laya_engine) -> None:
        self._engine_factory = engine_factory
        self._engine: Any = None
        self._predict_lock = threading.Lock()
        self._ready = False

    @property
    def created(self) -> bool:
        return self._engine is not None

    @property
    def ready(self) -> bool:
        """True once the warm-up predict has completed, i.e. the model is loaded."""
        return self._ready

    def warm_up(self) -> None:
        """Creates the Laya Router and runs one short predict so the checkpoint is loaded
        (and downloaded on first run) before any real routing request arrives."""
        if self._engine is None:
            self._engine = self._engine_factory()
        self._predict(WARM_UP_PROMPT)
        self._ready = True

    def classify(self, prompt: str) -> TierDecision:
        if not self._ready:
            raise RoutingError(BAD_GATEWAY, "The Laya model is not loaded yet.")

        try:
            result = self._predict(prompt)
        except Exception as e:
            raise RoutingError(BAD_GATEWAY, f"The Laya predict call failed: {e}") from e

        answer = _get(_get(result, "answers"), QUESTION_KEY)
        choice = _get(answer, "choice")
        try:
            tier = WorkerTier(str(choice).lower())
        except ValueError:
            raise RoutingError(BAD_GATEWAY, f"Laya returned a choice that was not offered: {choice!r}") from None

        probabilities = _get(answer, "probabilities")
        return TierDecision(tier=tier, probabilities=dict(probabilities) if probabilities else None)

    def _predict(self, prompt: str) -> Any:
        with self._predict_lock:
            return self._engine.predict(prompt, QUESTIONS)
