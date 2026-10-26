package koboolean.layarouter.service;

import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.layarouter.config.LayaRouterProperties.FallbackPolicy;
import koboolean.layarouter.config.LayaRouterProperties.ModelCapabilityRule;
import koboolean.layarouter.config.LayaRouterProperties.Weights;
import koboolean.layarouter.config.LayaRouterProperties;
import koboolean.layarouter.error.RoutingRejectedException;
import koboolean.layarouter.laya.WorkerTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Selects one Worker from the candidates Console supplied - never from anywhere else. Everything
 * about picking a Worker that is not Laya's job lives here.
 * <p>
 * RoutingHints are enforced exactly like the other Router examples: {@code requiredProvider},
 * {@code gpuRequired} and {@code privateData} are hard filters (422 when nothing is left) and
 * {@code preferredModel} is a soft preference.
 * <p>
 * For the tier Laya asked for, the Workers of the closest tier that is at least as capable are
 * used (a SMALL request does not occupy a POWERFUL Worker while a SMALL one exists). When no
 * Worker is capable enough the {@link FallbackPolicy} decides. Within the chosen tier the Worker
 * with the lowest Load Score wins - the same normalization, missing-metric handling, external
 * penalty and tie-break as router-static-example's {@code StaticRouterServiceImpl}.
 */
@Component
public class WorkerSelector {

    private static final Logger log = LoggerFactory.getLogger(WorkerSelector.class);

    private static final HttpStatus UNPROCESSABLE_ENTITY = HttpStatus.valueOf(422);
    private static final BigDecimal USAGE_PERCENTAGE_SCALE = BigDecimal.valueOf(100);
    /** Score of a Worker that reports no metric at all - treated as fully loaded. */
    private static final double UNKNOWN_LOAD_SCORE = 1.0;

    private final List<CompiledRule> rules;
    private final WorkerTier defaultTier;
    private final Weights weights;
    private final FallbackPolicy fallbackPolicy;
    private final double externalPenalty;

    public WorkerSelector(LayaRouterProperties properties) {
        this.rules = properties.getModelCapabilities().stream().map(CompiledRule::of).toList();
        this.defaultTier = properties.getDefaultTier();
        this.weights = properties.getWeights();
        this.fallbackPolicy = properties.getFallbackPolicy();
        this.externalPenalty = properties.getExternalPenalty();
    }

    // --- RoutingHints filtering (mirrors the other Router examples' hard-constraint policy) --

    public List<WorkerCandidate> filterByHints(List<WorkerCandidate> candidates, RoutingHints hints) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "No worker candidates were provided for routing.");
        }
        if (hints == null) {
            return candidates;
        }

        List<WorkerCandidate> eligible = candidates;

        if (hints.requiredProvider() != null && !hints.requiredProvider().isBlank()) {
            eligible = eligible.stream()
                    .filter(candidate -> hints.requiredProvider().equalsIgnoreCase(candidate.provider()))
                    .toList();
            if (eligible.isEmpty()) {
                throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                        "No worker candidate matches the required provider: " + hints.requiredProvider());
            }
        }

        if (Boolean.TRUE.equals(hints.gpuRequired())) {
            eligible = eligible.stream()
                    .filter(candidate -> candidate.gpu() != null && !candidate.gpu().isBlank())
                    .toList();
            if (eligible.isEmpty()) {
                throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                        "gpuRequired routing hint is set but no worker candidate reports a GPU.");
            }
        }

        if (hints.isPrivateData()) {
            eligible = eligible.stream().filter(this::isSafeForPrivateData).toList();
            if (eligible.isEmpty()) {
                throw new RoutingRejectedException(RoutingRejectedException.PRIVATE_DATA_NO_LOCAL_WORKER,
                        "privateData routing hint is set but no worker candidate is LOCAL-only.");
            }
        }

        // preferredModel is a soft preference: it narrows the candidate set only if doing so
        // would not eliminate every remaining candidate.
        if (hints.preferredModel() != null && !hints.preferredModel().isBlank()) {
            List<WorkerCandidate> matched = eligible.stream()
                    .filter(candidate -> modelsOf(candidate).stream()
                            .anyMatch(model -> hints.preferredModel().equalsIgnoreCase(model.modelName())))
                    .toList();
            eligible = matched.isEmpty() ? eligible : matched;
        }

        return eligible;
    }

    // Fail closed: no models, or any model that is not known to be LOCAL, is unsafe.
    private boolean isSafeForPrivateData(WorkerCandidate candidate) {
        List<ModelInfo> models = modelsOf(candidate);
        return !models.isEmpty() && models.stream().allMatch(model -> model.executionLocation() == ExecutionLocation.LOCAL);
    }

    // --- Capability tier ---------------------------------------------------------------

    /** Maps a model name to the tier it can serve, from configuration - no model name is hardcoded here. */
    WorkerTier tierOfModel(String modelName) {
        String name = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        return rules.stream()
                .filter(rule -> rule.pattern.matcher(name).matches())
                .map(CompiledRule::tier)
                .findFirst()
                .orElse(defaultTier);
    }

    /** A Worker is as capable as its most capable model. */
    WorkerTier tierOfWorker(WorkerCandidate candidate) {
        return modelsOf(candidate).stream()
                .map(model -> tierOfModel(model.modelName()))
                .max(Comparator.naturalOrder())
                .orElse(defaultTier);
    }

    public WorkerCandidate select(WorkerTier tier, List<WorkerCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "No worker candidates were provided for routing.");
        }

        Map<WorkerTier, List<WorkerCandidate>> byTier = new EnumMap<>(WorkerTier.class);
        for (WorkerCandidate candidate : candidates) {
            byTier.computeIfAbsent(tierOfWorker(candidate), t -> new ArrayList<>()).add(candidate);
        }

        // EnumMap iterates in capability order, so the first capable tier is the closest one.
        WorkerTier chosenTier = byTier.keySet().stream().filter(t -> t.isAtLeast(tier)).findFirst().orElse(null);
        if (chosenTier == null) {
            if (fallbackPolicy != FallbackPolicy.FALLBACK) {
                throw new ResponseStatusException(UNPROCESSABLE_ENTITY,
                        "No worker candidate satisfies the required capability tier: " + tier.label());
            }
            chosenTier = byTier.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
            log.warn("No worker candidate satisfies tier {}; falling back to the highest available tier {}.",
                    tier.label(), chosenTier.label());
        }

        return selectByScore(byTier.get(chosenTier));
    }

    // --- Load Score (same as router-static-example) --------------------------------------

    private WorkerCandidate selectByScore(List<WorkerCandidate> candidates) {
        BigDecimal maxRequestCount = maxOf(candidates, c -> toBigDecimal(c.currentRequestCount()));
        BigDecimal maxQueueSize = maxOf(candidates, c -> toBigDecimal(c.queueSize()));
        BigDecimal maxLatency = maxOf(candidates, WorkerCandidate::averageLatency);

        return candidates.stream()
                .map(candidate -> new ScoredCandidate(candidate,
                        score(candidate, maxRequestCount, maxQueueSize, maxLatency)))
                .min(TIE_BREAKER)
                .orElseThrow()
                .candidate();
    }

    private double score(WorkerCandidate candidate, BigDecimal maxRequestCount,
                         BigDecimal maxQueueSize, BigDecimal maxLatency) {
        Double[] values = {
                ratio(toBigDecimal(candidate.currentRequestCount()), maxRequestCount),
                ratio(toBigDecimal(candidate.queueSize()), maxQueueSize),
                ratio(candidate.averageLatency(), maxLatency),
                percentage(computeUsage(candidate)),
                percentage(memoryUsage(candidate)),
        };
        double[] metricWeights = {
                weights.getRequestCount(), weights.getQueueSize(), weights.getLatency(),
                weights.getGpuUsage(), weights.getVramUsage(),
        };

        // A missing metric is excluded, not defaulted to 0, and its weight is redistributed over
        // the metrics the Worker does report - otherwise a Worker would look idle just because it
        // reports less. A Worker reporting nothing at all is scored as fully loaded.
        double weightedSum = 0.0;
        double availableWeight = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                weightedSum += values[i] * metricWeights[i];
                availableWeight += metricWeights[i];
            }
        }
        double loadScore = availableWeight > 0 ? weightedSum / availableWeight : UNKNOWN_LOAD_SCORE;
        // A Worker that may send the request data to an external AI provider wins only when it is
        // clearly less loaded - same rule as router-static-example's external-penalty.
        return isSafeForPrivateData(candidate) ? loadScore : loadScore + externalPenalty;
    }

    // Usage of whatever the Worker runs inference on - same rule as router-static-example: its GPU
    // and VRAM, or CPU and system memory on a CPU-only Worker (system memory also for a GPU with
    // unified memory, which reports no VRAM).
    private BigDecimal computeUsage(WorkerCandidate candidate) {
        return hasGpu(candidate) ? candidate.gpuUsage() : candidate.cpuUsage();
    }

    private BigDecimal memoryUsage(WorkerCandidate candidate) {
        return hasGpu(candidate) && candidate.vramUsage() != null ? candidate.vramUsage() : candidate.memoryUsage();
    }

    private boolean hasGpu(WorkerCandidate candidate) {
        return candidate.gpu() != null && !candidate.gpu().isBlank();
    }

    private Double ratio(BigDecimal value, BigDecimal max) {
        if (value == null) {
            return null;
        }
        if (max == null || max.signum() == 0) {
            return 0.0;
        }
        return value.divide(max, MathContext.DECIMAL64).doubleValue();
    }

    private Double percentage(BigDecimal value) {
        return value == null ? null : value.divide(USAGE_PERCENTAGE_SCALE, MathContext.DECIMAL64).doubleValue();
    }

    private BigDecimal toBigDecimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private BigDecimal maxOf(List<WorkerCandidate> candidates, Function<WorkerCandidate, BigDecimal> extractor) {
        return candidates.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    private static List<ModelInfo> modelsOf(WorkerCandidate candidate) {
        return candidate.models() == null ? List.of() : candidate.models();
    }

    // Deterministic tie-break: score, queueSize, currentRequestCount, averageLatency, workerId -
    // never random, so identical input always yields the same Worker.
    private static final Comparator<ScoredCandidate> TIE_BREAKER = Comparator
            .comparingDouble(ScoredCandidate::score)
            .thenComparing(s -> s.candidate().queueSize() == null ? Integer.MAX_VALUE : s.candidate().queueSize())
            .thenComparing(s -> s.candidate().currentRequestCount() == null ? Integer.MAX_VALUE : s.candidate().currentRequestCount())
            .thenComparing(s -> s.candidate().averageLatency() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : s.candidate().averageLatency())
            .thenComparing(s -> s.candidate().workerId());

    private record ScoredCandidate(WorkerCandidate candidate, double score) {
    }

    private record CompiledRule(Pattern pattern, WorkerTier tier) {

        // Glob -> regex: '*' is any run of characters, '?' one character, everything else literal.
        static CompiledRule of(ModelCapabilityRule rule) {
            StringBuilder regex = new StringBuilder();
            for (char c : rule.getPattern().toLowerCase(Locale.ROOT).toCharArray()) {
                switch (c) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return new CompiledRule(Pattern.compile(regex.toString(), Pattern.DOTALL), rule.getTier());
        }
    }
}
