package koboolean.layarouter;

import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.enums.NodeStatus;
import koboolean.layarouter.laya.LayaTierClassifier;
import koboolean.layarouter.laya.TierDecision;
import koboolean.layarouter.laya.WorkerTier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The external contract Console relies on, over real HTTP: {@code POST /api/v1/route} returns
 * only the selected workerId, {@code GET /api/v1/health} is infra-node's, and both require the
 * node API key. Laya is mocked; LayaModelIntegrationTest covers the real model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouterHttpContractTest {

    private static final String WORKERS = """
            [{"workerId": 1, "name": "worker-1", "provider": "OLLAMA",
              "models": [{"provider": "OLLAMA", "modelName": "gemma3:4b", "executionLocation": "LOCAL"}],
              "currentRequestCount": 0, "queueSize": 0, "averageLatency": 120.5, "someFutureField": "ignored"},
             {"workerId": 2, "name": "worker-2", "provider": "OLLAMA",
              "models": [{"provider": "OLLAMA", "modelName": "qwen3:8b", "executionLocation": "LOCAL"}],
              "currentRequestCount": 0, "queueSize": 0, "averageLatency": 120.5}]""";

    @LocalServerPort
    private int port;

    @Value("${infra.node.api-key}")
    private String apiKey;

    @MockitoBean
    private LayaTierClassifier classifier;

    @Test
    void routeReturnsOnlyTheSelectedWorkerId() throws Exception {
        when(classifier.classify(contains("hello"))).thenReturn(new TierDecision(WorkerTier.MEDIUM, Map.of()));

        HttpResponse<String> response = post(routingRequest(WORKERS), apiKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.shared().readTree(response.body()).propertyNames()).containsExactly("workerId");
        assertThat(JsonMapper.shared().readTree(response.body()).get("workerId").asLong()).isEqualTo(2L);
    }

    @Test
    void routeRejectsNoWorkerCandidatesWithoutCallingLaya() throws Exception {
        assertThat(post(routingRequest("[]"), apiKey).statusCode()).isEqualTo(422);
        verify(classifier, never()).classify(any());
    }

    @Test
    void routeReportsALayaFailureAsBadGateway() throws Exception {
        when(classifier.classify(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        assertThat(post(routingRequest(WORKERS), apiKey).statusCode()).isEqualTo(502);
    }

    @Test
    void apiRequiresTheNodeApiKey() throws Exception {
        assertThat(post(routingRequest(WORKERS), null).statusCode()).isEqualTo(401);
        assertThat(post(routingRequest(WORKERS), "wrong").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/health", null).statusCode()).isEqualTo(401);
    }

    @Test
    void refusesAPrivateDataRequestWithNoLocalWorkerWithAMachineReadableCode() throws Exception {
        String externalOnly = WORKERS.replace("\"LOCAL\"", "\"EXTERNAL\"");
        String body = routingRequest(externalOnly).replace("\"routing\": null", "\"routing\": {\"privateData\": true}");

        HttpResponse<String> response = post(body, apiKey);

        assertThat(response.statusCode()).isEqualTo(422);
        var json = JsonMapper.shared().readTree(response.body());
        assertThat(json.get("code").asString()).isEqualTo("PRIVATE_DATA_NO_LOCAL_WORKER");
        assertThat(json.get("status").asInt()).isEqualTo(422);
        assertThat(json.get("path").asString()).isEqualTo("/api/v1/route");
        verify(classifier, never()).classify(any());
    }

    @Test
    void healthFollowsTheNodeHealthContract() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", apiKey);

        assertThat(response.statusCode()).isEqualTo(200);
        NodeHealthResponse health = JsonMapper.shared().readValue(response.body(), NodeHealthResponse.class);
        assertThat(health.status()).isEqualTo(NodeStatus.UP);
        assertThat(health.system().memory().total()).isPositive();
        assertThat(health.runtime()).isNotNull();
    }

    private static String routingRequest(String workers) {
        return """
                {"sessionId": "session-1", "messages": [{"role": "USER", "content": "hello"}],
                 "options": null, "routing": null, "tools": [], "toolChoice": null, "workers": %s}"""
                .formatted(workers);
    }

    private HttpResponse<String> post(String body, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/route"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return send(request, key);
    }

    private HttpResponse<String> get(String path, String key) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET(), key);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String key) throws Exception {
        if (key != null) {
            request.header("X-Infra-Api-Key", key);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
