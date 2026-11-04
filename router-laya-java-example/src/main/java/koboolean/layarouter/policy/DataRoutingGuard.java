package koboolean.layarouter.policy;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.enums.DataRoutingPolicy;
import koboolean.layarouter.error.RoutingRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides whether a request must stay on LOCAL-only Workers, from {@code routing.privateData} and
 * the Team's {@link DataRoutingPolicy}. It only restricts the candidates: Laya's complexity tier and
 * the Worker selection that follow are unchanged.
 * <table>
 *     <tr><th>privateData</th><th>policy</th><th>non-LOCAL Workers</th></tr>
 *     <tr><td>true</td><td>any</td><td>no</td></tr>
 *     <tr><td>false</td><td>INTERNAL_ONLY</td><td>no</td></tr>
 *     <tr><td>false</td><td>AUTO (or missing)</td><td>only when judged NOT_SENSITIVE</td></tr>
 *     <tr><td>false</td><td>EXTERNAL_ALLOWED</td><td>yes</td></tr>
 * </table>
 */
@Component
public class DataRoutingGuard {

    private static final Logger log = LoggerFactory.getLogger(DataRoutingGuard.class);

    private final SensitivityChecker sensitivityChecker;

    public DataRoutingGuard(SensitivityChecker sensitivityChecker) {
        this.sensitivityChecker = sensitivityChecker;
    }

    public static DataRoutingPolicy policyOf(RoutingRequest request) {
        return request.dataRoutingPolicy() == null ? DataRoutingPolicy.AUTO : request.dataRoutingPolicy();
    }

    public Optional<LocalOnlyRequirement> localOnlyRequirement(RoutingRequest request) {
        // privateData wins under every policy, and makes judging the content pointless.
        if (request.routing() != null && request.routing().isPrivateData()) {
            return Optional.of(new LocalOnlyRequirement(RoutingRejectedException.PRIVATE_DATA_NO_LOCAL_WORKER,
                    "privateData routing hint is set but no worker candidate is LOCAL-only."));
        }
        return switch (policyOf(request)) {
            case EXTERNAL_ALLOWED -> Optional.empty();
            case INTERNAL_ONLY -> Optional.of(new LocalOnlyRequirement(RoutingRejectedException.INTERNAL_ONLY_NO_LOCAL_WORKER,
                    "dataRoutingPolicy is INTERNAL_ONLY but no worker candidate is LOCAL-only."));
            case AUTO -> switch (judge(request)) {
                case NOT_SENSITIVE -> Optional.empty();
                case SENSITIVE -> Optional.of(new LocalOnlyRequirement(RoutingRejectedException.SENSITIVE_DATA_NO_LOCAL_WORKER,
                        "The request was judged sensitive but no worker candidate is LOCAL-only."));
                case UNKNOWN -> Optional.of(new LocalOnlyRequirement(RoutingRejectedException.SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER,
                        "The request could not be judged not sensitive and no worker candidate is LOCAL-only."));
            };
        };
    }

    // Fail closed: a failure or a missing verdict is UNKNOWN, never NOT_SENSITIVE. Only the
    // exception type is logged - its message could quote the request content.
    private SensitivityVerdict judge(RoutingRequest request) {
        SensitivityVerdict verdict;
        try {
            verdict = sensitivityChecker.check(request);
        } catch (RuntimeException e) {
            log.warn("event=sensitivity_check_failed sessionId={} error={}; restricting to LOCAL-only workers.",
                    request.sessionId(), e.getClass().getName());
            return SensitivityVerdict.UNKNOWN;
        }
        if (verdict == null) {
            log.warn("event=sensitivity_check_failed sessionId={} error=no verdict; restricting to LOCAL-only workers.",
                    request.sessionId());
            return SensitivityVerdict.UNKNOWN;
        }
        return verdict;
    }
}
