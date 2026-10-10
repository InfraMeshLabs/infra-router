package koboolean.staticrouter.health;

import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.enums.NodeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIRECT (default) Router health is unchanged by OUTBOUND HEALTH reporting: Console still pulls
 * {@code GET /api/v1/health} (infra-node's HealthCheckController) with the node API key, and no
 * outbound connection - hence no HEALTH push - exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectHealthEndpointTest {

    @LocalServerPort
    private int port;

    @Value("${infra.node.api-key}")
    private String apiKey;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthEndpoint_returnsNodeHealthResponse() throws Exception {
        HttpResponse<String> response = get(apiKey);

        assertThat(response.statusCode()).isEqualTo(200);
        NodeHealthResponse health = JsonMapper.shared().readValue(response.body(), NodeHealthResponse.class);
        assertThat(health.status()).isEqualTo(NodeStatus.UP);
        assertThat(health.system().memory().total()).isPositive();
        assertThat(health.runtime()).isNotNull();
    }

    @Test
    void healthEndpoint_stillRequiresApiKey() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(401);
    }

    @Test
    void directMode_hasNoOutboundConnection() {
        assertThat(context.getBeanProvider(OutboundNodeConnection.class).getIfAvailable()).isNull();
    }

    private HttpResponse<String> get(String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health"));
        if (key != null) {
            request.header("X-Infra-Api-Key", key);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
