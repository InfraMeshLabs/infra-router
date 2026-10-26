package koboolean.layarouter.service;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.enums.ChatRole;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Builds Laya's input from {@code RoutingRequest.messages}.
 * <p>
 * Laya is only ever given conversation text - never the raw RoutingRequest JSON and never Worker
 * runtime metrics. This version uses the last user message; multi-turn context can be added here
 * later without touching the rest of the Router.
 */
@Component
public class PromptBuilder {

    public Optional<String> build(RoutingRequest request) {
        List<ChatMessage> messages = request.messages() == null ? List.of() : request.messages();
        return lastContent(messages, true)
                // e.g. a tool-result-only turn: judge the most recent text instead.
                .or(() -> lastContent(messages, false));
    }

    private Optional<String> lastContent(List<ChatMessage> messages, boolean userOnly) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (userOnly && message.role() != ChatRole.USER) {
                continue;
            }
            if (message.content() != null && !message.content().isBlank()) {
                return Optional.of(message.content().strip());
            }
        }
        return Optional.empty();
    }
}
