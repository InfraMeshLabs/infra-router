package koboolean.jevrouter.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A single eligible Worker's runtime state, as sent to Jev inside the Choice question's
 * {@code state} array. Deliberately narrower than
 * {@code com.inframesh.node.dto.router.WorkerCandidate}: only the fields relevant to a
 * routing decision are forwarded to the external API, never raw Entity/DB-shaped data.
 */
public record JevWorkerState(
        Long workerId,
        String name,
        List<String> models,
        String provider,
        Integer currentRequestCount,
        Integer queueSize,
        BigDecimal averageLatency,
        BigDecimal gpuUsage,
        BigDecimal vramUsage
) {
}
