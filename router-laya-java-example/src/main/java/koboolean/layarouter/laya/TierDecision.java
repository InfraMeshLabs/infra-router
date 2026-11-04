package koboolean.layarouter.laya;

import java.util.Map;

/**
 * Laya's answer reduced to what routing uses.
 *
 * @param tier          the tier Laya chose - the most probable one
 * @param probabilities Laya's probability per tier label. The checkpoints ship uncalibrated
 *                      temperatures, so these rank the tiers but are not true likelihoods; tune
 *                      {@code router.laya.confidence-threshold} against real traffic.
 */
public record TierDecision(WorkerTier tier, Map<String, Double> probabilities) {

    /**
     * The tier to route to. Without a threshold, Laya's choice. With one, the lowest tier whose
     * cumulative probability - it and every tier below it - reaches the threshold, but never
     * below Laya's choice: an ambiguous answer escalates to a more capable tier, never down.
     */
    public WorkerTier requiredTier(Double confidenceThreshold) {
        if (confidenceThreshold == null || probabilities == null || probabilities.isEmpty()) {
            return tier;
        }
        double cumulative = 0.0;
        for (WorkerTier candidate : WorkerTier.values()) {
            cumulative += probabilities.getOrDefault(candidate.label(), 0.0);
            if (cumulative >= confidenceThreshold) {
                return candidate.isAtLeast(tier) ? candidate : tier;
            }
        }
        // Rounding left the total just under the threshold: only the most capable tier is safe.
        return WorkerTier.values()[WorkerTier.values().length - 1];
    }
}
