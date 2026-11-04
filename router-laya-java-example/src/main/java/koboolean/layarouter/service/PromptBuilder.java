package koboolean.layarouter.service;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ChatRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds Laya's input by rendering the user prompt template ({@code prompts/routing-user-prompt.st})
 * with the same variables as router-spring-ai-example - {@code {messages}},
 * {@code {preferredModel}}, {@code {requiredProvider}}, {@code {candidates}} - plus
 * {@code {message}}, the last user message (or the most recent text, for a tool-result-only turn).
 * <p>
 * A request with no message content at all yields no prompt.
 */
@Component
public class PromptBuilder {

    private static final String NONE = "none";

    private final String userPromptTemplate;

    public PromptBuilder(@Value("classpath:prompts/routing-user-prompt.st") Resource userPromptResource) {
        this.userPromptTemplate = readResource(userPromptResource);
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Laya prompt: " + resource, e);
        }
    }

    public Optional<String> build(RoutingRequest request, List<WorkerCandidate> candidates) {
        List<ChatMessage> messages = request.messages() == null ? List.of() : request.messages();
        return lastContent(messages, true)
                // e.g. a tool-result-only turn: judge the most recent text instead.
                .or(() -> lastContent(messages, false))
                .map(message -> render(request, messages, message, candidates));
    }

    private String render(RoutingRequest request, List<ChatMessage> messages, String message,
                          List<WorkerCandidate> candidates) {
        RoutingHints hints = request.routing();

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("message", message);
        variables.put("messages", renderMessages(messages));
        variables.put("preferredModel", hints != null && hints.preferredModel() != null ? hints.preferredModel() : NONE);
        variables.put("requiredProvider", hints != null && hints.requiredProvider() != null ? hints.requiredProvider() : NONE);
        variables.put("candidates", renderCandidates(candidates));

        String prompt = userPromptTemplate;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            prompt = prompt.replace("{" + variable.getKey() + "}", variable.getValue());
        }
        return prompt.strip();
    }

    private String renderMessages(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append("- [").append(message.role()).append("] ").append(message.content()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    // Same fields and order as router-spring-ai-example's renderCandidates.
    private String renderCandidates(List<WorkerCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (WorkerCandidate candidate : candidates == null ? List.<WorkerCandidate>of() : candidates) {
            builder.append("- workerId=").append(candidate.workerId())
                    .append(", name=").append(candidate.name())
                    .append(", provider=").append(candidate.provider())
                    .append(", models=").append(candidate.models())
                    .append(", framework=").append(candidate.framework())
                    .append(", frameworkVersion=").append(candidate.frameworkVersion())
                    .append(", currentRequestCount=").append(candidate.currentRequestCount())
                    .append(", queueSize=").append(candidate.queueSize())
                    .append(", averageLatency=").append(candidate.averageLatency())
                    .append(", throughput=").append(candidate.throughput())
                    .append(", errorCount=").append(candidate.errorCount())
                    .append(", cpu=").append(candidate.cpu())
                    .append(", cpuUsage=").append(candidate.cpuUsage())
                    .append(", memory=").append(candidate.memory())
                    .append(", memoryUsage=").append(candidate.memoryUsage())
                    .append(", gpu=").append(candidate.gpu())
                    .append(", gpuUsage=").append(candidate.gpuUsage())
                    .append(", vram=").append(candidate.vram())
                    .append(", vramUsage=").append(candidate.vramUsage())
                    .append('\n');
        }
        return builder.toString().stripTrailing();
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
