"""DataRoutingPolicy: whether a request must stay on LOCAL-only Workers.

Same policy as router-laya-java-example's ``DataRoutingGuard``. It only restricts the
candidates: Laya's complexity tier and the Worker selection that follow are unchanged.

| privateData | policy            | non-LOCAL Workers             |
|-------------|-------------------|-------------------------------|
| true        | any               | no                            |
| false       | INTERNAL_ONLY     | no                            |
| false       | AUTO (or missing) | only when judged NOT_SENSITIVE |
| false       | EXTERNAL_ALLOWED  | yes                           |
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from enum import Enum
from typing import Callable, Optional

from app.models import (
    INTERNAL_ONLY_NO_LOCAL_WORKER,
    PRIVATE_DATA_NO_LOCAL_WORKER,
    SENSITIVE_DATA_NO_LOCAL_WORKER,
    SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER,
    DataRoutingPolicy,
    RoutingRequest,
)

log = logging.getLogger(__name__)


class SensitivityVerdict(str, Enum):
    SENSITIVE = "SENSITIVE"
    NOT_SENSITIVE = "NOT_SENSITIVE"
    # Not judged with confidence - treated exactly like SENSITIVE (fail closed).
    UNKNOWN = "UNKNOWN"


# Judges whether a request's content is sensitive, for DataRoutingPolicy.AUTO. It must run
# locally - the content must never be sent to an external AI to be judged - and should bound
# its own latency (raise on a timeout). A raised exception or a None result counts as UNKNOWN.
SensitivityChecker = Callable[[RoutingRequest], Optional[SensitivityVerdict]]


def not_configured(_: RoutingRequest) -> SensitivityVerdict:
    """The default: this Router ships no sensitivity judge. Laya judges what a text is about, not
    whether it holds an actual secret, so it is not used for this - see "Sensitive data detection"
    in the root README. Every AUTO request is therefore restricted to LOCAL-only Workers."""
    return SensitivityVerdict.UNKNOWN


@dataclass(frozen=True)
class LocalOnlyRequirement:
    """The request may only go to a LOCAL-only Worker; code and reason are what the Router
    refuses with when none is left. The reason never contains request content."""

    code: str
    reason: str


PRIVATE_DATA_REQUIREMENT = LocalOnlyRequirement(
    PRIVATE_DATA_NO_LOCAL_WORKER, "privateData routing hint is set but no worker candidate is LOCAL-only.")


def policy_of(request: RoutingRequest) -> DataRoutingPolicy:
    # Missing / null (an older Console) is AUTO, as in infra-node's RoutingRequest.
    return request.data_routing_policy or DataRoutingPolicy.AUTO


class DataRoutingGuard:
    def __init__(self, sensitivity_checker: SensitivityChecker = not_configured) -> None:
        self._sensitivity_checker = sensitivity_checker

    def local_only_requirement(self, request: RoutingRequest) -> Optional[LocalOnlyRequirement]:
        # privateData wins under every policy, and makes judging the content pointless.
        if request.routing is not None and request.routing.private_data is True:
            return PRIVATE_DATA_REQUIREMENT

        policy = policy_of(request)
        if policy is DataRoutingPolicy.EXTERNAL_ALLOWED:
            return None
        if policy is DataRoutingPolicy.INTERNAL_ONLY:
            return LocalOnlyRequirement(
                INTERNAL_ONLY_NO_LOCAL_WORKER, "dataRoutingPolicy is INTERNAL_ONLY but no worker candidate is LOCAL-only.")

        verdict = self._judge(request)
        if verdict is SensitivityVerdict.NOT_SENSITIVE:
            return None
        if verdict is SensitivityVerdict.SENSITIVE:
            return LocalOnlyRequirement(
                SENSITIVE_DATA_NO_LOCAL_WORKER, "The request was judged sensitive but no worker candidate is LOCAL-only.")
        return LocalOnlyRequirement(
            SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER,
            "The request could not be judged not sensitive and no worker candidate is LOCAL-only.")

    def _judge(self, request: RoutingRequest) -> SensitivityVerdict:
        # Fail closed: a failure or a missing verdict is UNKNOWN, never NOT_SENSITIVE. Only the
        # exception type is logged - its message could quote the request content.
        try:
            verdict = self._sensitivity_checker(request)
        except Exception as e:  # noqa: BLE001 - any failure must fail closed
            log.warning("event=sensitivity_check_failed sessionId=%s error=%s; restricting to LOCAL-only workers.",
                        request.session_id, type(e).__name__)
            return SensitivityVerdict.UNKNOWN
        if not isinstance(verdict, SensitivityVerdict):
            log.warning("event=sensitivity_check_failed sessionId=%s error=no verdict; restricting to LOCAL-only workers.",
                        request.session_id)
            return SensitivityVerdict.UNKNOWN
        return verdict
