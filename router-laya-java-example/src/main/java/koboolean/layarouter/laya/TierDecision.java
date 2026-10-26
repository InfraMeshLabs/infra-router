package koboolean.layarouter.laya;

import java.util.Map;

/**
 * Laya's answer reduced to what routing uses.
 *
 * @param tier          the tier Laya chose
 * @param probabilities debug/logging only. Confidence is deliberately not carried: the current
 *                      checkpoints ship uncalibrated temperatures, so routing uses the choice alone
 */
public record TierDecision(WorkerTier tier, Map<String, Double> probabilities) {
}
