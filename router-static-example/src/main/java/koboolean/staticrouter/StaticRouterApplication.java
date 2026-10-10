package koboolean.staticrouter;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import koboolean.staticrouter.service.RouterService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StaticRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StaticRouterApplication.class, args);
    }

    /**
     * Router REQUEST handler: serves routing REQUESTs received over the OUTBOUND persistent
     * connection with the same {@link RouterService} DIRECT's {@code POST /api/v1/route} uses -
     * only the transport differs. The connection itself (WebSocket, auth, heartbeat, reconnect,
     * lifecycle) comes from infra-node's Outbound Connection SDK, and this bean is only picked up
     * when {@code inframesh.node.connection-mode: OUTBOUND}.
     */
    @Bean
    public NodeRequestHandler<RoutingRequest, RoutingResponse> outboundRequestHandler(RouterService routerService) {
        return NodeRequestHandler.of(RoutingRequest.class, routerService::route);
    }
}
