package koboolean.layarouter.laya;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TierDecisionTest {

    private static TierDecision decision(WorkerTier tier, double small, double medium, double powerful) {
        return new TierDecision(tier, Map.of("small", small, "medium", medium, "powerful", powerful));
    }

    @Test
    void withoutAThresholdRoutesToLayasChoice() {
        assertThat(decision(WorkerTier.MEDIUM, 0.30, 0.36, 0.34).requiredTier(null)).isEqualTo(WorkerTier.MEDIUM);
    }

    @Test
    void keepsAConfidentChoice() {
        assertThat(decision(WorkerTier.MEDIUM, 0.05, 0.90, 0.05).requiredTier(0.8)).isEqualTo(WorkerTier.MEDIUM);
    }

    @Test
    void escalatesAnAmbiguousChoice() {
        assertThat(decision(WorkerTier.MEDIUM, 0.30, 0.36, 0.34).requiredTier(0.8)).isEqualTo(WorkerTier.POWERFUL);
    }

    @Test
    void neverRoutesBelowLayasChoice() {
        // small + medium = 0.40 already reaches 0.3, but Laya chose powerful.
        assertThat(decision(WorkerTier.POWERFUL, 0.00, 0.40, 0.60).requiredTier(0.3)).isEqualTo(WorkerTier.POWERFUL);
    }

    @Test
    void routesToLayasChoiceWhenThereAreNoProbabilities() {
        assertThat(new TierDecision(WorkerTier.SMALL, Map.of()).requiredTier(0.8)).isEqualTo(WorkerTier.SMALL);
    }

    @Test
    void routesToTheMostCapableTierWhenRoundingKeepsTheTotalUnderTheThreshold() {
        assertThat(decision(WorkerTier.SMALL, 0.5, 0.2, 0.2999).requiredTier(1.0)).isEqualTo(WorkerTier.POWERFUL);
    }
}
