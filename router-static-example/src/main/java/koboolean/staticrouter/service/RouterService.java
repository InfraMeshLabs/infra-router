package koboolean.staticrouter.service;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;

/**
 * Selects the Worker that should handle a {@link RoutingRequest}.
 * <p>
 * This is a Routing Decision only — the Router never calls the selected
 * Worker itself. Console is responsible for invoking the Worker with the
 * {@code workerId} returned here.
 */
public interface RouterService {

    RoutingResponse route(RoutingRequest request);
}
