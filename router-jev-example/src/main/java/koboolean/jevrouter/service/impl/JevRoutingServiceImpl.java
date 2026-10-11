package koboolean.jevrouter.service.impl;

import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.jevrouter.client.JevClient;
import koboolean.jevrouter.client.JevClientException;
import koboolean.jevrouter.dto.JevChoiceResponse.ChoiceAnswer;
import koboolean.jevrouter.dto.JevWorkerState;
import koboolean.jevrouter.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Worker selection via Jev's Choice primitive - a typed-decision Layer, not a policy
 * engine. {@code requiredProvider}, {@code gpuRequired} and {@code privateData} are
 * enforced as hard filters in code before Jev is ever called, mirroring the policy in the
 * {@code router-spring-ai-example} and {@code router-static-example} modules: Jev only
 * ever sees candidates that have already passed every required constraint, and can never
 * be the thing that decides whether a policy is satisfied. {@code preferredModel} is a
 * soft preference and is also resolved in code - what the request needs is something the
 * Router can determine deterministically, so it is not left to Jev to interpret.
 * <p>
 * Jev is asked a single Choice question - "which of these eligible Workers fits this
 * request best" - with one option per remaining candidate (keyed {@code worker_<id>}) and
 * their {@code currentRequestCount}/{@code queueSize}/{@code averageLatency}/
 * {@code gpuUsage}/{@code vramUsage} as state. The returned choice is trusted only if it
 * names a candidate that was actually offered; an unknown choice is treated exactly like a
 * failed call.
 * <p>
 * Jev is an external HTTP call and can time out, be rate-limited, or fail outright. Rather
 * than let a routing decision hang indefinitely or fail the whole request over a transient
 * API problem, any {@link JevClientException} (or an unrecognized worker choice) falls
 * back to a small deterministic ordering - lowest {@code queueSize}, then lowest
 * {@code currentRequestCount}, then lowest {@code averageLatency}, then lowest
 * {@code workerId} - logged loudly rather than silently. This is intentionally not the
 * full weighted/configurable algorithm in {@code router-static-example}: duplicating that
 * module's scoring here would create two competing sources of truth for the same policy,
 * so this fallback is deliberately minimal. See the module README for the full rationale.
 */
@Service
public class JevRoutingServiceImpl implements RouterService {

    private static final Logger log = LoggerFactory.getLogger(JevRoutingServiceImpl.class);

    private static final HttpStatus UNPROCESSABLE_ENTITY = HttpStatus.valueOf(422);
    private static final String WORKER_OPTION_PREFIX = "worker_";

    private static final Comparator<WorkerCandidate> FALLBACK_ORDER = Comparator
            .comparing((WorkerCandidate c) -> c.queueSize() == null ? Integer.MAX_VALUE : c.queueSize())
            .thenComparing(c -> c.currentRequestCount() == null ? Integer.MAX_VALUE : c.currentRequestCount())
            .thenComparing(c -> c.averageLatency() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : c.averageLatency())
            .thenComparing(WorkerCandidate::workerId);

    private final JevClient jevClient;
    private final String instructions;

    public JevRoutingServiceImpl(
            JevClient jevClient,
            @Value("classpath:prompts/worker-choice-instructions.txt") Resource instructionsResource
    ) {
        this.jevClient = jevClient;
        this.instructions = readResource(instructionsResource);
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Jev instructions prompt: " + resource, e);
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
        eligible = preferModel(eligible, hints);

        WorkerCandidate selected = selectWorker(eligible);
        return new RoutingResponse(selected.workerId());
    }

    // --- RoutingHints filtering (mirrors the other Router examples' hard-constraint policy) --

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

    // preferredModel is a soft preference: it narrows the candidate set only if doing so
    // would not eliminate every remaining candidate.
    private List<WorkerCandidate> preferModel(List<WorkerCandidate> candidates, RoutingHints hints) {
        if (hints == null || hints.preferredModel() == null || hints.preferredModel().isBlank()) {
            return candidates;
        }

        List<WorkerCandidate> matched = candidates.stream()
                .filter(candidate -> supportsModel(candidate, hints.preferredModel()))
                .toList();

        return matched.isEmpty() ? candidates : matched;
    }

    private boolean supportsModel(WorkerCandidate candidate, String preferredModel) {
        List<ModelInfo> models = candidate.models();
        if (models == null) {
            return false;
        }

        return models.stream().anyMatch(model -> preferredModel.equalsIgnoreCase(model.modelName()));
    }

    // --- Jev decision + fallback -------------------------------------------------------

    private WorkerCandidate selectWorker(List<WorkerCandidate> eligible) {
        try {
            ChoiceAnswer answer = jevClient.chooseWorker(toState(eligible), instructions, toCriteria(eligible));
            log.debug("Jev selected worker: {} confidence: {}", answer.choice(), answer.confidence());

            return parseWorkerId(answer.choice())
                    .flatMap(workerId -> findCandidate(eligible, workerId))
                    .orElseGet(() -> {
                        log.warn("Jev selected an option that was not among the offered candidates: '{}'; "
                                + "falling back to a deterministic selection.", answer.choice());
                        return fallbackSelect(eligible);
                    });
        } catch (JevClientException e) {
            log.warn("Jev routing call failed ({}); falling back to a deterministic selection.", e.getMessage());
            return fallbackSelect(eligible);
        }
    }

    private List<JevWorkerState> toState(List<WorkerCandidate> eligible) {
        return eligible.stream()
                .map(c -> new JevWorkerState(c.workerId(), c.name(), modelNames(c), c.provider(),
                        c.currentRequestCount(), c.queueSize(), c.averageLatency(), c.gpuUsage(), c.vramUsage()))
                .toList();
    }

    private Map<String, String> toCriteria(List<WorkerCandidate> eligible) {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (WorkerCandidate candidate : eligible) {
            criteria.put(WORKER_OPTION_PREFIX + candidate.workerId(), describe(candidate));
        }
        return criteria;
    }

    private String describe(WorkerCandidate candidate) {
        return "name=" + candidate.name()
                + ", provider=" + candidate.provider()
                + ", models=" + modelNames(candidate)
                + ", currentRequestCount=" + candidate.currentRequestCount()
                + ", queueSize=" + candidate.queueSize()
                + ", averageLatency=" + candidate.averageLatency()
                + ", gpuUsage=" + candidate.gpuUsage()
                + ", vramUsage=" + candidate.vramUsage();
    }

    private List<String> modelNames(WorkerCandidate candidate) {
        if (candidate.models() == null) {
            return List.of();
        }
        return candidate.models().stream().map(ModelInfo::modelName).toList();
    }

    private Optional<Long> parseWorkerId(String choice) {
        if (choice == null || !choice.startsWith(WORKER_OPTION_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(choice.substring(WORKER_OPTION_PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<WorkerCandidate> findCandidate(List<WorkerCandidate> eligible, Long workerId) {
        return eligible.stream().filter(candidate -> candidate.workerId().equals(workerId)).findFirst();
    }

    private WorkerCandidate fallbackSelect(List<WorkerCandidate> eligible) {
        return eligible.stream().min(FALLBACK_ORDER).orElseThrow();
    }
}
