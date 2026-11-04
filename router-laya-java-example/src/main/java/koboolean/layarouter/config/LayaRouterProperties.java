package koboolean.layarouter.config;

import jakarta.annotation.PostConstruct;
import koboolean.layarouter.laya.WorkerTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Router configuration under {@code router.laya.*}: where the Laya checkpoints and ONNX graphs
 * live, how a model name maps to a capability tier, and how Workers of one tier are compared.
 * <p>
 * {@link #validate()} fails application startup on an inconsistent configuration rather than
 * routing with a silently skewed policy.
 */
@ConfigurationProperties(prefix = "router.laya")
public class LayaRouterProperties {

    private static final double TOLERANCE = 0.001;

    /** Holds {@code english/} and {@code multilingual/}, as laya-java's prepare_checkpoint.py writes them. */
    private Path checkpointsRoot;
    /** Holds {@code english/laya.onnx} and {@code multilingual/laya.onnx}. */
    private Path onnxRoot;
    /** Checkpoints loaded at startup, so no request pays a cold load and a missing graph fails fast. */
    private List<String> preload = new ArrayList<>(List.of("english", "multilingual"));
    /** How many checkpoints may be resident at once - a memory ceiling, not a cache hint. */
    private int maxLoaded = 2;

    /**
     * Unset: route to the tier Laya finds most probable. Set (0, 1]: route to the lowest tier
     * whose cumulative probability reaches it, escalating ambiguous answers - see
     * {@link koboolean.layarouter.laya.TierDecision#requiredTier(Double)}.
     */
    private Double confidenceThreshold;

    /**
     * Added to the Load Score of a Worker that is not LOCAL-only, as router-static-example's
     * {@code router.static.external-penalty}.
     */
    private double externalPenalty = 0.1;

    private FallbackPolicy fallbackPolicy = FallbackPolicy.FALLBACK;
    /** Tier of a model that matches no rule, and of a Worker that reports no models. */
    private WorkerTier defaultTier = WorkerTier.SMALL;
    private List<ModelCapabilityRule> modelCapabilities = new ArrayList<>();
    private Weights weights = new Weights();

    public enum FallbackPolicy {
        /** No Worker satisfies the required tier -> routing fails (422). */
        STRICT,
        /** No Worker satisfies the required tier -> use the highest tier that is available. */
        FALLBACK
    }

    /** Case-insensitive glob ({@code *}, {@code ?}) matched against {@code ModelInfo.modelName}. */
    public static class ModelCapabilityRule {

        private String pattern;
        private WorkerTier tier;

        public ModelCapabilityRule() {
        }

        public ModelCapabilityRule(String pattern, WorkerTier tier) {
            this.pattern = pattern;
            this.tier = tier;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public WorkerTier getTier() {
            return tier;
        }

        public void setTier(WorkerTier tier) {
            this.tier = tier;
        }
    }

    /** Same five metrics and defaults as router-static-example's {@code router.static.weights}. */
    public static class Weights {

        private double requestCount = 0.30;
        private double queueSize = 0.25;
        private double latency = 0.20;
        private double gpuUsage = 0.15;
        private double vramUsage = 0.10;

        public double getRequestCount() {
            return requestCount;
        }

        public void setRequestCount(double requestCount) {
            this.requestCount = requestCount;
        }

        public double getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(double queueSize) {
            this.queueSize = queueSize;
        }

        public double getLatency() {
            return latency;
        }

        public void setLatency(double latency) {
            this.latency = latency;
        }

        public double getGpuUsage() {
            return gpuUsage;
        }

        public void setGpuUsage(double gpuUsage) {
            this.gpuUsage = gpuUsage;
        }

        public double getVramUsage() {
            return vramUsage;
        }

        public void setVramUsage(double vramUsage) {
            this.vramUsage = vramUsage;
        }
    }

    @PostConstruct
    public void validate() {
        double[] values = {weights.requestCount, weights.queueSize, weights.latency, weights.gpuUsage, weights.vramUsage};
        double sum = 0.0;
        for (double value : values) {
            if (value < 0) {
                throw new IllegalStateException("router.laya.weights must not be negative.");
            }
            sum += value;
        }
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalStateException("router.laya.weights must sum to 1.0 but was " + sum);
        }
        if (maxLoaded < 1) {
            throw new IllegalStateException("router.laya.max-loaded must be at least 1 but was " + maxLoaded);
        }
        if (confidenceThreshold != null && (confidenceThreshold <= 0.0 || confidenceThreshold > 1.0)) {
            throw new IllegalStateException("router.laya.confidence-threshold must be in (0, 1] but was " + confidenceThreshold);
        }
        if (externalPenalty < 0.0 || externalPenalty > 1.0) {
            throw new IllegalStateException("router.laya.external-penalty must be in [0, 1] but was " + externalPenalty);
        }
        for (ModelCapabilityRule rule : modelCapabilities) {
            if (rule.getPattern() == null || rule.getPattern().isBlank() || rule.getTier() == null) {
                throw new IllegalStateException("Every router.laya.model-capabilities entry needs a pattern and a tier.");
            }
        }
    }

    public Path getCheckpointsRoot() {
        return checkpointsRoot;
    }

    public void setCheckpointsRoot(Path checkpointsRoot) {
        this.checkpointsRoot = checkpointsRoot;
    }

    public Path getOnnxRoot() {
        return onnxRoot;
    }

    public void setOnnxRoot(Path onnxRoot) {
        this.onnxRoot = onnxRoot;
    }

    public List<String> getPreload() {
        return preload;
    }

    public void setPreload(List<String> preload) {
        this.preload = preload;
    }

    public int getMaxLoaded() {
        return maxLoaded;
    }

    public void setMaxLoaded(int maxLoaded) {
        this.maxLoaded = maxLoaded;
    }

    public Double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(Double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getExternalPenalty() {
        return externalPenalty;
    }

    public void setExternalPenalty(double externalPenalty) {
        this.externalPenalty = externalPenalty;
    }

    public FallbackPolicy getFallbackPolicy() {
        return fallbackPolicy;
    }

    public void setFallbackPolicy(FallbackPolicy fallbackPolicy) {
        this.fallbackPolicy = fallbackPolicy;
    }

    public WorkerTier getDefaultTier() {
        return defaultTier;
    }

    public void setDefaultTier(WorkerTier defaultTier) {
        this.defaultTier = defaultTier;
    }

    public List<ModelCapabilityRule> getModelCapabilities() {
        return modelCapabilities;
    }

    public void setModelCapabilities(List<ModelCapabilityRule> modelCapabilities) {
        this.modelCapabilities = modelCapabilities;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }
}
