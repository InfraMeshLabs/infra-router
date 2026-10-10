package koboolean.staticrouter.outbound;

import com.inframesh.node.connection.NodeRequestHandler;
import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import koboolean.staticrouter.service.RouterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Router REQUEST handler this example registers with infra-node's Outbound Connection SDK:
 * it must reuse the existing RouterService (the same one behind DIRECT's /api/v1/route), and -
 * with the default DIRECT configuration - no outbound connection may be created at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundRequestHandlerTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private NodeRequestHandler<RoutingRequest, RoutingResponse> handler;

    @MockitoBean
    private RouterService routerService;

    @Test
    void directModeByDefault_createsNoOutboundConnection() {
        assertThat(context.getBeanProvider(OutboundNodeConnection.class).getIfAvailable()).isNull();
    }

    @Test
    void handler_acceptsRoutingRequestAndDelegatesToRouterService() {
        when(routerService.route(any(RoutingRequest.class))).thenReturn(new RoutingResponse(7L));

        assertThat(handler.requestType()).isEqualTo(RoutingRequest.class);
        assertThat(handler.handle(new RoutingRequest(List.of(ChatMessage.user("hello")), null, null)).workerId())
                .isEqualTo(7L);
    }
}
