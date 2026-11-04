package koboolean.layarouter.policy;

/** What a {@link SensitivityChecker} concluded about a request's content. */
public enum SensitivityVerdict {

    /** The content holds sensitive data: LOCAL-only Workers only. */
    SENSITIVE,

    /** The content was judged, with confidence, to hold no sensitive data. */
    NOT_SENSITIVE,

    /** Not judged with confidence. Treated exactly like {@link #SENSITIVE} - fail closed. */
    UNKNOWN
}
