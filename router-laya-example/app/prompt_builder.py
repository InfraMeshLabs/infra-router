"""Turns a RoutingRequest into the text Laya judges."""

from __future__ import annotations

from typing import Optional

from app.models import ChatMessage, RoutingRequest

USER_ROLE = "USER"


class PromptBuilder:
    """Builds Laya's input from ``RoutingRequest.messages``.

    Laya is only ever given conversation text - never the raw RoutingRequest JSON and
    never Worker runtime metrics. This version uses the last user message; multi-turn
    context can be added here later without touching the rest of the Router.
    """

    def build(self, request: RoutingRequest) -> Optional[str]:
        messages = request.messages or []
        return (
            self._last_content(messages, user_only=True)
            # e.g. a tool-result-only turn: judge the most recent text instead.
            or self._last_content(messages, user_only=False)
        )

    @staticmethod
    def _last_content(messages: list[ChatMessage], user_only: bool) -> Optional[str]:
        for message in reversed(messages):
            if user_only and (message.role or "").upper() != USER_ROLE:
                continue
            if message.content and message.content.strip():
                return message.content.strip()
        return None
