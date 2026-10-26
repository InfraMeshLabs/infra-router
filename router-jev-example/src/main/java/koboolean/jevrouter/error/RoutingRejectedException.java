package koboolean.jevrouter.error;

import com.inframesh.node.connection.NodeErrorCarrier;
import com.inframesh.node.dto.connection.NodeError;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A routing request refused for a policy reason, with a machine-readable {@link #getCode() code}
 * Console can tell apart from a Router failure:
 * <ul>
 *     <li>DIRECT - the 422 error body carries {@code "code"} ({@link RoutingErrorHandler});</li>
 *     <li>OUTBOUND - infra-node (0.1.5+) sends {@link #nodeError()}, so {@code NodeError.code} is the
 *     same code.</li>
 * </ul>
 * The reason never contains the request content.
 */
public class RoutingRejectedException extends ResponseStatusException implements NodeErrorCarrier {

    /** {@code privateData} is set but no eligible Worker is LOCAL-only. */
    public static final String PRIVATE_DATA_NO_LOCAL_WORKER = "PRIVATE_DATA_NO_LOCAL_WORKER";
    /** The request was judged to hold sensitive data but no eligible Worker is LOCAL-only. */
    public static final String SENSITIVE_DATA_NO_LOCAL_WORKER = "SENSITIVE_DATA_NO_LOCAL_WORKER";

    private final String code;

    public RoutingRejectedException(String code, String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @Override
    public NodeError nodeError() {
        return new NodeError(code, getReason());
    }
}
