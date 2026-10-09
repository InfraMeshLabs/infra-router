"""Router configuration, loaded once at startup from ``application.yml`` and the environment."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Optional
from uuid import UUID

import yaml

from app.models import WorkerTier

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "application.yml"


class FallbackPolicy(str, Enum):
    # No Worker satisfies the required tier -> routing fails (422).
    STRICT = "STRICT"
    # No Worker satisfies the required tier -> use the highest tier that is available.
    FALLBACK = "FALLBACK"


def _parse_bool(name: str, value: object) -> bool:
    # Strict on purpose: a typo must not silently switch authentication on or off.
    text = str(value).strip().lower()
    if text in ("true", "false"):
        return text == "true"
    raise ValueError(f"{name} must be true or false but was {value!r}.")


@dataclass(frozen=True)
class ScoreWeights:
    """Same five metrics and defaults as router-static-example's ``router.static.weights``."""

    request_count: float = 0.30
    queue_size: float = 0.25
    latency: float = 0.20
    gpu_usage: float = 0.15
    vram_usage: float = 0.10

    def __post_init__(self) -> None:
        values = (self.request_count, self.queue_size, self.latency, self.gpu_usage, self.vram_usage)
        if any(value < 0 for value in values):
            raise ValueError("router.laya.weights must not be negative.")
        if abs(sum(values) - 1.0) > 1e-6:
            raise ValueError(f"router.laya.weights must sum to 1.0 but sum to {sum(values)}.")


@dataclass(frozen=True)
class ModelCapabilityRule:
    # Case-insensitive glob (fnmatch) matched against ModelInfo.modelName.
    pattern: str
    tier: WorkerTier


@dataclass(frozen=True)
class Settings:
    # Whether /api/v1/* requires X-Infra-Api-Key. Turning it off is always an explicit
    # choice (security.enabled: false) - a missing key never disables it.
    security_enabled: bool = True
    api_key: Optional[str] = None
    fallback_policy: FallbackPolicy = FallbackPolicy.FALLBACK
    # Tier of a model that matches no rule, and of a Worker that reports no models.
    default_tier: WorkerTier = WorkerTier.SMALL
    weights: ScoreWeights = field(default_factory=ScoreWeights)
    model_capabilities: tuple[ModelCapabilityRule, ...] = ()

    def __post_init__(self) -> None:
        if not self.security_enabled:
            return
        if not (self.api_key and self.api_key.strip()):
            raise ValueError(
                "security.enabled is true but no API key is configured. Set INFRA_NODE_API_KEY, "
                "or disable authentication explicitly with security.enabled: false "
                "(SECURITY_ENABLED=false) in a trusted development environment."
            )
        # infra-node binds infra.node.api-key as a java.util.UUID and compares the header
        # with its canonical (lowercase) string form; do the same.
        try:
            canonical = str(UUID(self.api_key.strip()))
        except ValueError:
            raise ValueError("INFRA_NODE_API_KEY must be a UUID, as issued by Infra Console.") from None
        object.__setattr__(self, "api_key", canonical)

    @staticmethod
    def load(path: Optional[Path] = None) -> "Settings":
        path = path or Path(os.environ.get("ROUTER_CONFIG", DEFAULT_CONFIG_PATH))
        with open(path, encoding="utf-8") as file:
            root = yaml.safe_load(file) or {}

        laya = (root.get("router") or {}).get("laya") or {}
        weights = laya.get("weights") or {}
        rules = tuple(
            ModelCapabilityRule(pattern=str(rule["pattern"]), tier=WorkerTier(str(rule["tier"]).lower()))
            for rule in laya.get("model-capabilities") or []
        )

        security = root.get("security") or {}
        security_enabled = _parse_bool(
            "security.enabled", os.environ.get("SECURITY_ENABLED") or security.get("enabled", True)
        )

        return Settings(
            security_enabled=security_enabled,
            api_key=os.environ.get("INFRA_NODE_API_KEY") or None,
            fallback_policy=FallbackPolicy(
                str(os.environ.get("ROUTER_LAYA_FALLBACK_POLICY") or laya.get("fallback-policy", "FALLBACK")).upper()
            ),
            default_tier=WorkerTier(str(laya.get("default-tier", "small")).lower()),
            weights=ScoreWeights(
                request_count=float(weights.get("request-count", 0.30)),
                queue_size=float(weights.get("queue-size", 0.25)),
                latency=float(weights.get("latency", 0.20)),
                gpu_usage=float(weights.get("gpu-usage", 0.15)),
                vram_usage=float(weights.get("vram-usage", 0.10)),
            ),
            model_capabilities=rules,
        )
