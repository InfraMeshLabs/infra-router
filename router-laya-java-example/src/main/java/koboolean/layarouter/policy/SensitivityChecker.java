package koboolean.layarouter.policy;

import com.inframesh.node.dto.router.RoutingRequest;

/**
 * Judges whether a request's content is sensitive, for {@code DataRoutingPolicy.AUTO}.
 * <p>
 * This Router ships no implementation: Laya judges what a text is about, not whether it holds an
 * actual secret, so it is not used for this (see "Sensitive data detection" in the root README).
 * Without a {@code SensitivityChecker} bean every AUTO request is {@link SensitivityVerdict#UNKNOWN}
 * and restricted to LOCAL-only Workers.
 * <p>
 * An implementation must run locally - the request content must never be sent to an external AI
 * to be judged - and should bound its own latency (throw on a timeout). Return
 * {@link SensitivityVerdict#NOT_SENSITIVE} only when confident; a thrown exception or a
 * {@code null} result counts as {@link SensitivityVerdict#UNKNOWN}.
 */
@FunctionalInterface
public interface SensitivityChecker {

    SensitivityVerdict check(RoutingRequest request);
}
