package koboolean.layarouter.policy;

/**
 * The request may only go to a LOCAL-only Worker. {@code code} and {@code reason} are what the
 * Router refuses with when no such Worker is left; the reason never contains request content.
 */
public record LocalOnlyRequirement(String code, String reason) {
}
