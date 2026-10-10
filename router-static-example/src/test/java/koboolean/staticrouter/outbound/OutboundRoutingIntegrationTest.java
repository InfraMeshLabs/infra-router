package koboolean.staticrouter.outbound;

import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.connection.NodeEnvelope;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import com.inframesh.node.enums.NodeMessageType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Router over infra-node's Outbound Connection SDK: boots the real static Router in OUTBOUND mode
 * against a fake Console socket - no Router-specific WebSocket/heartbeat/reconnect code exists -
 * and drives a routing REQUEST through the SDK into the existing (unmocked) routing service, which
 * answers with a RESPONSE carrying the RoutingResponse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundRoutingIntegrationTest {

    private static final UUID NODE_ID = UUID.fromString("2f9c7a1e-8b4d-4c3a-a6e5-0d1b2c3e4f5a");
    private static final String CREDENTIAL = "router-test-credential";
    private static final FakeConsoleServer CONSOLE = startConsole();

    @Autowired
    private OutboundNodeConnection connection;

    @DynamicPropertySource
    static void outboundProperties(DynamicPropertyRegistry registry) {
        registry.add("inframesh.node.connection-mode", () -> "OUTBOUND");
        registry.add("inframesh.node.console-url", CONSOLE::baseUrl);
        registry.add("inframesh.node.node-id", NODE_ID::toString);
        registry.add("inframesh.node.credential", () -> CREDENTIAL);
        registry.add("inframesh.node.outbound.heartbeat-interval", () -> "30s");
    }

    @AfterAll
    static void stopConsole() throws IOException {
        CONSOLE.close();
    }

    @BeforeEach
    void awaitConnected() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!connection.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void handshake_authenticatesWithSameHeadersAsAWorker() throws InterruptedException {
        // Only this test drains the handshake queue, so the context's single handshake is still there.
        Map<String, String> headers = CONSOLE.awaitHandshakeHeaders(1);
        assertThat(headers).isNotNull();
        assertThat(headers.get("X-Infra-Node-Id")).isEqualTo(NODE_ID.toString());
        assertThat(headers.get("X-Infra-Node-Credential")).isEqualTo(CREDENTIAL);
    }

    @Test
    void routingRequest_isDecidedByExistingRoutingServiceAndResponded() throws Exception {
        WorkerCandidate busy = candidate(1L, 8, 5, 2400, 90, 85);
        WorkerCandidate idle = candidate(2L, 1, 0, 400, 20, 30);

        CONSOLE.sendTextFrame(requestFrame("route-1", new RoutingRequest(
                List.of(ChatMessage.user("hello")), null, null, List.of(busy, idle))));

        JsonNode response = awaitEnvelope(NodeMessageType.RESPONSE, "route-1");
        assertThat(response.path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        assertThat(response.path("payload").path("workerId").asLong()).isEqualTo(2L);
    }

    @Test
    void routingFailure_isReportedAsErrorEnvelope() throws Exception {
        CONSOLE.sendTextFrame(requestFrame("route-empty", new RoutingRequest(
                List.of(ChatMessage.user("hello")), null, null, List.of())));

        JsonNode error = awaitEnvelope(NodeMessageType.ERROR, "route-empty");
        assertThat(error.path("payload").path("message").asString()).contains("No worker candidates");
    }

    private static FakeConsoleServer startConsole() {
        try {
            return new FakeConsoleServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkerCandidate candidate(Long workerId, int requestCount, int queueSize, int latencyMs,
                                      int gpuUsagePercent, int vramUsagePercent) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null,
                List.of(new ModelInfo("OLLAMA", "qwen3", ExecutionLocation.LOCAL)), "OLLAMA",
                "SPRING_AI", "1.0.0",
                requestCount, queueSize, BigDecimal.valueOf(latencyMs), BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                "gpu", BigDecimal.valueOf(gpuUsagePercent),
                "vram", BigDecimal.valueOf(vramUsagePercent));
    }

    private String requestFrame(String requestId, RoutingRequest request) {
        return JsonMapper.shared().writeValueAsString(new NodeEnvelope<>(
                UUID.randomUUID().toString(), NodeMessageType.REQUEST, NODE_ID, Instant.now(), requestId, request));
    }

    private JsonNode awaitEnvelope(NodeMessageType type, String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (type.name().equals(envelope.path("type").asString()) && requestId.equals(envelope.path("requestId").asString())) {
                return envelope;
            }
        }
        throw new AssertionError("Timed out waiting for " + type + " requestId=" + requestId);
    }
}
