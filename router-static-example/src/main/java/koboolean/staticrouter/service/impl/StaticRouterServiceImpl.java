package koboolean.staticrouter.service.impl;

import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.staticrouter.config.StaticRouterProperties;
import koboolean.staticrouter.config.StaticRouterWeights;
import koboolean.staticrouter.error.RoutingRejectedException;
import koboolean.staticrouter.service.RouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Deterministic, AI-free Worker selection.
 * <p>
 * {@code requiredProvider}, {@code gpuRequired} and {@code privateData} are enforced as
 * hard filters first, mirroring the policy in {@code AiRouterServiceImpl} in the
 * {@code router-spring-ai-example} module: a Router must never violate an explicit
 * constraint. {@code preferredModel} is a soft preference — it narrows the candidate set
 * only when at least one remaining candidate actually offers that model, otherwise every
 * candidate stays eligible.
 * <p>
 * The remaining candidates are scored with a Weighted Load Score built from five runtime
 * metrics: {@code currentRequestCount}, {@code queueSize}, {@code averageLatency}, compute
 * usage and memory usage. {@code currentRequestCount}, {@code queueSize} and
 * {@code averageLatency} have no known absolute maximum, so they are normalized relative to
 * the highest value among the eligible candidates. Usage is reported by Console as a 0-100
 * percentage, so it is normalized by dividing by 100. A lower score means a Worker has more
 * spare capacity; the Worker with the minimum score is selected.
 * <p>
 * Compute and memory usage measure whatever the Worker runs inference on: {@code gpuUsage}
 * and {@code vramUsage} on a GPU Worker, {@code cpuUsage} and {@code memoryUsage} on a
 * CPU-only Worker, and {@code memoryUsage} for a GPU with unified memory (Apple Silicon
 * reports no VRAM). They are weighted by {@code router.static.weights.gpu-usage} and
 * {@code vram-usage}.
 * <p>
 * Any metric can be {@code null} for a given Worker - Console does not populate
 * {@code queueSize} or {@code averageLatency} yet. Rather than defaulting a missing metric to
 * 0, which would make a Worker look idle because it reports less, a missing metric is
 * excluded from that Worker's score and its weight is redistributed proportionally across
 * that Worker's remaining metrics. A Worker that reports no metric at all is scored as fully
 * loaded, as Console's own LEAST_BUSY strategy does: unknown load is never assumed to be idle.
 * <p>
 * Where a Worker runs its models ({@code ModelInfo.executionLocation}) is scored too. Without
 * {@code privateData} an EXTERNAL Worker stays eligible, but a Worker that is not LOCAL-only -
 * any EXTERNAL model, or a model or Worker whose location is unknown - has
 * {@code router.static.external-penalty} added to its score: the request data may leave to an
 * outside AI provider, so such a Worker wins only when it is clearly less loaded. With
 * {@code privateData} it is not eligible at all.
 * <p>
 * {@code throughput} and {@code errorCount} are deliberately not scored: Console does not
 * populate them yet, and {@code errorCount} is a lifetime total that would penalize a Worker
 * for its age rather than its current health.
 */
@Service
public class StaticRouterServiceImpl implements RouterService {

    private static final Logger log = LoggerFactory.getLogger(StaticRouterServiceImpl.class);

    private static final HttpStatus UNPROCESSABLE_ENTITY = HttpStatus.valueOf(422);
    private static final BigDecimal USAGE_PERCENTAGE_SCALE = BigDecimal.valueOf(100);
    /** Score of a Worker that reports no metric at all - treated as fully loaded. */
    private static final double UNKNOWN_LOAD_SCORE = 1.0;

    private final StaticRouterWeights weights;
    private final double externalPenalty;

    public StaticRouterServiceImpl(StaticRouterWeights weights, StaticRouterProperties properties) {
        this.weights = weights;
        this.externalPenalty = properties.getExternalPenalty();
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

        WorkerCandidate selected = selectByScore(eligible);
        return new RoutingResponse(selected.workerId());
    }

    // --- RoutingHints filtering (mirrors AiRouterServiceImpl's hard-constraint policy) --

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
            throw new RoutingRejectedException(RoutingRejectedException.PRIVATE_DATA_NO_LOCAL_WORKER,
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

    // --- Load Score ------------------------------------------------------------------

    private WorkerCandidate selectByScore(List<WorkerCandidate> candidates) {
        BigDecimal maxRequestCount = maxOf(candidates, c -> toBigDecimal(c.currentRequestCount()));
        BigDecimal maxQueueSize = maxOf(candidates, c -> toBigDecimal(c.queueSize()));
        BigDecimal maxLatency = maxOf(candidates, WorkerCandidate::averageLatency);

        List<ScoredCandidate> scored = candidates.stream()
                .map(candidate -> score(candidate, maxRequestCount, maxQueueSize, maxLatency))
                .sorted(TIE_BREAKER)
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("Routing candidates: {}", scored.size());
            scored.forEach(s -> log.debug(
                    "worker={} request={} queue={} latency={} compute={} memory={} localOnly={} score={}",
                    s.candidate.workerId(), s.normalizedRequestCount, s.normalizedQueueSize,
                    s.normalizedLatency, s.normalizedGpuUsage, s.normalizedVramUsage, s.localOnly, s.score));
        }

        WorkerCandidate selected = scored.get(0).candidate;
        log.info("Selected worker: {}", selected.workerId());
        return selected;
    }

    private ScoredCandidate score(WorkerCandidate candidate, BigDecimal maxRequestCount,
                                   BigDecimal maxQueueSize, BigDecimal maxLatency) {
        Double normalizedRequestCount = ratio(toBigDecimal(candidate.currentRequestCount()), maxRequestCount);
        Double normalizedQueueSize = ratio(toBigDecimal(candidate.queueSize()), maxQueueSize);
        Double normalizedLatency = ratio(candidate.averageLatency(), maxLatency);
        Double normalizedGpuUsage = percentage(computeUsage(candidate));
        Double normalizedVramUsage = percentage(memoryUsage(candidate));

        double weightedSum = 0.0;
        double availableWeight = 0.0;

        weightedSum += weighted(normalizedRequestCount, weights.getRequestCount());
        availableWeight += normalizedRequestCount != null ? weights.getRequestCount() : 0.0;

        weightedSum += weighted(normalizedQueueSize, weights.getQueueSize());
        availableWeight += normalizedQueueSize != null ? weights.getQueueSize() : 0.0;

        weightedSum += weighted(normalizedLatency, weights.getLatency());
        availableWeight += normalizedLatency != null ? weights.getLatency() : 0.0;

        weightedSum += weighted(normalizedGpuUsage, weights.getGpuUsage());
        availableWeight += normalizedGpuUsage != null ? weights.getGpuUsage() : 0.0;

        weightedSum += weighted(normalizedVramUsage, weights.getVramUsage());
        availableWeight += normalizedVramUsage != null ? weights.getVramUsage() : 0.0;

        // A missing metric is excluded rather than defaulted to 0, and the remaining
        // weight is renormalized to sum to 1 - see the class-level note. A candidate with
        // no available metric at all is scored as fully loaded.
        double loadScore = availableWeight > 0 ? weightedSum / availableWeight : UNKNOWN_LOAD_SCORE;
        boolean localOnly = isSafeForPrivateData(candidate);
        double score = localOnly ? loadScore : loadScore + externalPenalty;

        return new ScoredCandidate(candidate, normalizedRequestCount, normalizedQueueSize,
                normalizedLatency, normalizedGpuUsage, normalizedVramUsage, localOnly, score);
    }

    // The processor the Worker runs inference on: its GPU if it has one, otherwise its CPU.
    private BigDecimal computeUsage(WorkerCandidate candidate) {
        return hasGpu(candidate) ? candidate.gpuUsage() : candidate.cpuUsage();
    }

    // VRAM on a GPU Worker; system memory on a CPU-only Worker and on a GPU with unified
    // memory, which reports no VRAM.
    private BigDecimal memoryUsage(WorkerCandidate candidate) {
        return hasGpu(candidate) && candidate.vramUsage() != null ? candidate.vramUsage() : candidate.memoryUsage();
    }

    private boolean hasGpu(WorkerCandidate candidate) {
        return candidate.gpu() != null && !candidate.gpu().isBlank();
    }

    private double weighted(Double normalizedValue, double weight) {
        return normalizedValue != null ? normalizedValue * weight : 0.0;
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

    // Deterministic tie-break, per the documented policy: lowest score, then lowest
    // queueSize, then lowest currentRequestCount, then lowest averageLatency, then the
    // lowest workerId - never random, so identical input always yields the same Worker.
    private static final Comparator<ScoredCandidate> TIE_BREAKER = Comparator
            .comparingDouble((ScoredCandidate s) -> s.score)
            .thenComparing(s -> s.candidate.queueSize() == null ? Integer.MAX_VALUE : s.candidate.queueSize())
            .thenComparing(s -> s.candidate.currentRequestCount() == null ? Integer.MAX_VALUE : s.candidate.currentRequestCount())
            .thenComparing(s -> s.candidate.averageLatency() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : s.candidate.averageLatency())
            .thenComparing(s -> s.candidate.workerId());

    private record ScoredCandidate(
            WorkerCandidate candidate,
            Double normalizedRequestCount,
            Double normalizedQueueSize,
            Double normalizedLatency,
            Double normalizedGpuUsage,
            Double normalizedVramUsage,
            boolean localOnly,
            double score
    ) {
    }
}
