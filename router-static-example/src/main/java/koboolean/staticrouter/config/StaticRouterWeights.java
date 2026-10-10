package koboolean.staticrouter.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-metric weights for {@code StaticRouterServiceImpl}'s Load Score, configured under
 * {@code router.static.weights} instead of being hardcoded so operators can retune them
 * without a code change.
 * <p>
 * {@link #validate()} fails application startup if the weights do not sum to 1.0, rather
 * than silently scoring with a skewed policy.
 */
@ConfigurationProperties(prefix = "router.static.weights")
public class StaticRouterWeights {

    private static final double TOLERANCE = 0.001;

    private double requestCount;
    private double queueSize;
    private double latency;
    private double gpuUsage;
    private double vramUsage;

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

    @PostConstruct
    public void validate() {
        double sum = requestCount + queueSize + latency + gpuUsage + vramUsage;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalStateException(
                    "router.static.weights must sum to 1.0 but was " + sum);
        }
    }
}
