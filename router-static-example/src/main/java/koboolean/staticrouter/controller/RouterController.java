package koboolean.staticrouter.controller;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import koboolean.staticrouter.service.RouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Router-only API: a Routing Decision, not an inference call.
 * <p>
 * {@code /route} here means "choose a Worker for this request" — it never
 * runs AI inference and it never calls a Worker itself. There is
 * intentionally no {@code /stream} endpoint: the Router is not a runtime.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RouterController {

    private final RouterService routerService;

    @PostMapping("/route")
    public RoutingResponse route(@RequestBody RoutingRequest request) {
        return routerService.route(request);
    }
}
