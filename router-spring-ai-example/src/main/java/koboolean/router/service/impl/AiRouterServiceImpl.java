package koboolean.router.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.router.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-native Worker selection.
 * <p>
 * {@code requiredProvider} and {@code gpuRequired} are enforced as hard filters in code
 * first, since a Router must never violate an explicit constraint just because a model
 * got confused. {@code requiredCapabilities} and {@code minimumVramBytes} are NOT
 * enforced: {@link WorkerCandidate} currently has no capability list and no numeric VRAM
 * capacity field to check them against ({@code vram} is a free-text description like
 * {@code "32GB"}, not bytes) — enforcing them would mean guessing at a protocol that
 * does not exist yet rather than reading one, so they are left unused pending a
 * {@code WorkerCandidate} extension.
 * <p>
 * {@code privateData} is enforced as a hard filter too, and it is Worker-level, not
 * model-level: the Router cannot force which model a Worker picks internally, so a
 * Worker that is merely capable of routing to an {@code EXTERNAL} model can never be
 * trusted with private data, even if it also offers a {@code LOCAL} one. A Worker is
 * excluded whenever its safety cannot be established from the given data (no models,
 * or a model with no {@code executionLocation}) — private routing fails closed rather
 * than trusting an unknown Worker.
 * <p>
 * The
 * remaining candidates — with every runtime field the Protocol provides, not a
 * hand-picked subset — are rendered into a prompt (loaded from {@code classpath:prompts},
 * never from Console) and handed to a chat model, which returns the workerId it judges
 * best.
 * <p>
 * The model's answer is never trusted blindly: it is accepted only if it names one of
 * the candidates that were actually offered. There is intentionally no automatic
 * fallback (e.g. to a least-loaded heuristic) when the model call fails, times out,
 * returns unparsable output, or names a worker that was never offered — those are
 * routing failures and are reported as such (502), not silently papered over with a
 * different algorithm. A fallback policy, if one is wanted, belongs in a deliberate
 * future design, not as an implicit side effect of AI failure.
 */
@Service
public class AiRouterServiceImpl implements RouterService {

    private static final Logger log = LoggerFactory.getLogger(AiRouterServiceImpl.class);

    private static final HttpStatus UNPROCESSABLE_ENTITY = HttpStatus.valueOf(422);
    private static final HttpStatus BAD_GATEWAY = HttpStatus.valueOf(502);
    private static final String NONE = "없음";

    private final ChatModel chatModel;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String systemPrompt;
    private final String userPromptTemplate;

    public AiRouterServiceImpl(
            ChatModel chatModel,
            @Value("classpath:prompts/routing-system-prompt.st") Resource systemPromptResource,
            @Value("classpath:prompts/routing-user-prompt.st") Resource userPromptResource
    ) {
        this.chatModel = chatModel;
        this.systemPrompt = readResource(systemPromptResource);
        this.userPromptTemplate = readResource(userPromptResource);
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load routing prompt: " + resource, e);
        }
    }

    @Override
    public RoutingResponse route(RoutingRequest request) {
        List<WorkerCandidate> candidates = request.workers();
        if (candidates == null || candidates.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "No worker candidates were provided for routing.");
        }

        RoutingHints hints = request.routing();
        List<WorkerCandidate> eligible = filterByRequiredProvider(candidates, hints);
        eligible = filterByGpuRequired(eligible, hints);
        eligible = filterByPrivateData(eligible, hints);

        // Filtering already left exactly one option - no ambiguity to ask the model to
        // resolve. This is deterministic pre-selection, not a fallback for AI failure.
//        if (eligible.size() == 1) {
//            return new RoutingResponse(eligible.get(0).workerId());
//        }

        Long selectedWorkerId = selectWithAi(request, eligible);

        WorkerCandidate selected = eligible.stream()
                .filter(candidate -> candidate.workerId().equals(selectedWorkerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(BAD_GATEWAY,
                        "The routing model selected a workerId that was not among the offered candidates: "
                                + selectedWorkerId));

        return new RoutingResponse(selected.workerId());
    }

    private List<WorkerCandidate> filterByRequiredProvider(List<WorkerCandidate> candidates, RoutingHints hints) {
        if (hints == null || hints.requiredProvider() == null || hints.requiredProvider().isBlank()) {
            return candidates;
        }

        List<WorkerCandidate> matched = candidates.stream()
                .filter(candidate -> hints.requiredProvider().equalsIgnoreCase(candidate.provider()))
                .toList();

        if (matched.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "No worker candidate matches the required provider: " + hints.requiredProvider());
        }

        return matched;
    }

    // gpuRequired maps onto WorkerCandidate.gpu() (present only when the worker reports a
    // GPU). requiredCapabilities and minimumVramBytes have no corresponding WorkerCandidate
    // field yet, so they are intentionally not enforced here - see the class-level note.
    private List<WorkerCandidate> filterByGpuRequired(List<WorkerCandidate> candidates, RoutingHints hints) {
        if (hints == null || !Boolean.TRUE.equals(hints.gpuRequired())) {
            return candidates;
        }

        List<WorkerCandidate> matched = candidates.stream()
                .filter(candidate -> candidate.gpu() != null && !candidate.gpu().isBlank())
                .toList();

        if (matched.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "gpuRequired routing hint is set but no worker candidate reports a GPU.");
        }

        return matched;
    }

    // privateData excludes a Worker entirely the moment any one of its models can run
    // EXTERNAL - a Worker choosing Qwen(LOCAL) or GPT(EXTERNAL) internally is just as
    // unsafe as one offering only GPT, since the Router cannot constrain that choice.
    // Unknown safety (no models, or a model with a null executionLocation) is treated
    // as unsafe rather than passed through, per fail-closed policy.
    private List<WorkerCandidate> filterByPrivateData(List<WorkerCandidate> candidates, RoutingHints hints) {
        if (hints == null || !hints.isPrivateData()) {
            return candidates;
        }

        List<WorkerCandidate> matched = candidates.stream()
                .filter(this::isSafeForPrivateData)
                .toList();

        if (matched.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "privateData routing hint is set but no worker candidate is LOCAL-only.");
        }

        return matched;
    }

    private boolean isSafeForPrivateData(WorkerCandidate candidate) {
        List<ModelInfo> models = candidate.models();
        if (models == null || models.isEmpty()) {
            return false;
        }

        return models.stream().allMatch(model -> model.executionLocation() == ExecutionLocation.LOCAL);
    }

    private Long selectWithAi(RoutingRequest request, List<WorkerCandidate> candidates) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(renderUserPrompt(request, candidates))
        ));

        ChatResponse response;
        try {
            // No per-call ChatOptions here: a portable ChatOptions on the Prompt replaces
            // the model's configured defaults wholesale (including which model to call)
            // instead of merging with them, so model/temperature stay in application.yml.
            response = chatModel.call(prompt);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY, "The routing model call failed: " + e.getMessage(), e);
        }

        Generation generation = response.getResult();
        String text = generation != null ? generation.getOutput().getText() : null;

        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(BAD_GATEWAY, "The routing model returned an empty response.");
        }

        return parseWorkerId(text);
    }

    private Long parseWorkerId(String text) {
        try {
            WorkerSelection selection = jsonMapper.readValue(extractJsonObject(text), WorkerSelection.class);
            if (selection.workerId() == null) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "The routing model response did not include a workerId: " + text);
            }
            return selection.workerId();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not parse routing model response as {{\"workerId\": <number>}}: {}", text);
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not parse the routing model's response as JSON.", e);
        }
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String renderUserPrompt(RoutingRequest request, List<WorkerCandidate> candidates) {
        RoutingHints hints = request.routing();

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("messages", renderMessages(request.messages()));
        variables.put("preferredModel", hints != null && hints.preferredModel() != null ? hints.preferredModel() : NONE);
        variables.put("requiredProvider", hints != null && hints.requiredProvider() != null ? hints.requiredProvider() : NONE);
        variables.put("candidates", renderCandidates(candidates));

        return new PromptTemplate(userPromptTemplate).render(variables);
    }

    private String renderMessages(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append("- [").append(message.role()).append("] ").append(message.content()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    // Every runtime field the Protocol offers is included, not a hand-picked subset -
    // which values matter is left to the model's reasoning, not decided here in code.
    private String renderCandidates(List<WorkerCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (WorkerCandidate candidate : candidates) {
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

    private record WorkerSelection(Long workerId) {
    }
}
