package koboolean.layarouter.laya;

import java.util.Locale;
import java.util.Optional;

/**
 * Request complexity / Worker capability class.
 * <p>
 * Internal to this Router - never part of the InfraMesh protocol. Declared from least to most
 * capable, so {@link #ordinal()} is the capability rank.
 */
public enum WorkerTier {

    SMALL,
    MEDIUM,
    POWERFUL;

    /** The option label Laya is offered and answers with. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isAtLeast(WorkerTier other) {
        return ordinal() >= other.ordinal();
    }

    /** The tier Laya's choice names, or empty for anything that was not offered. */
    public static Optional<WorkerTier> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        for (WorkerTier tier : values()) {
            if (tier.label().equalsIgnoreCase(label.strip())) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
