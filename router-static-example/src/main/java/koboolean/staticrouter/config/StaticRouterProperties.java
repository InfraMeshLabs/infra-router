package koboolean.staticrouter.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static Router policy under {@code router.static} other than the Load Score weights
 * ({@link StaticRouterWeights}).
 */
@ConfigurationProperties(prefix = "router.static")
public class StaticRouterProperties {

    /**
     * Added to the Load Score of a Worker that is not LOCAL-only - one whose models may send the
     * request data to an external AI provider, or whose location is unknown. A Worker that is
     * not LOCAL-only therefore wins only when it is at least this much less loaded. {@code 0}
     * ignores the location; {@code 1} picks such a Worker only when no LOCAL-only one remains.
     */
    private double externalPenalty = 0.1;

    public double getExternalPenalty() {
        return externalPenalty;
    }

    public void setExternalPenalty(double externalPenalty) {
        this.externalPenalty = externalPenalty;
    }

    @PostConstruct
    public void validate() {
        if (externalPenalty < 0.0 || externalPenalty > 1.0) {
            throw new IllegalStateException("router.static.external-penalty must be in [0, 1] but was " + externalPenalty);
        }
    }
}
