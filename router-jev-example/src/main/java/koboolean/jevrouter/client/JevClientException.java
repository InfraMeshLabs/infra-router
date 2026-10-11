package koboolean.jevrouter.client;

/**
 * Any failure calling the Jev API: timeout, connection error, non-2xx status, or an
 * unusable response body. {@code JevRoutingService} catches this at a single boundary and
 * falls back to a deterministic selection - see its class-level note.
 */
public class JevClientException extends RuntimeException {

    public JevClientException(String message) {
        super(message);
    }

    public JevClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
