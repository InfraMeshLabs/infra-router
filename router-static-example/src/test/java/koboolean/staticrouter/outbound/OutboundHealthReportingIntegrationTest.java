package koboolean.staticrouter.outbound;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inframesh.node.connection.OutboundNodeConnection;
import com.inframesh.node.dto.NodeHealthResponse;
import com.inframesh.node.enums.NodeMessageType;
import com.inframesh.node.enums.NodeStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OUTBOUND Router HEALTH reporting over infra-node's Outbound Connection SDK: the real static
 * Router pushes its runtime health - the same {@link NodeHealthResponse} that DIRECT serves from
 * {@code GET /api/v1/health}, collected by infra-node's NodeHealthService - as a HEALTH envelope,
 * exactly like an OUTBOUND Worker. No Router-specific health DTO, message type, scheduler or
 * WebSocket code exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboundHealthReportingIntegrationTest {

    private static final UUID NODE_ID = UUID.fromString("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f");
    private static final String CREDENTIAL = "router-health-secret-credential";
    private static final FakeConsoleServer CONSOLE = startConsole();

    @Autowired
    private OutboundNodeConnection connection;

    @DynamicPropertySource
    static void outboundProperties(DynamicPropertyRegistry registry) {
        registry.add("inframesh.node.connection-mode", () -> "OUTBOUND");
        registry.add("inframesh.node.console-url", CONSOLE::baseUrl);
        registry.add("inframesh.node.node-id", NODE_ID::toString);
        registry.add("inframesh.node.credential", () -> CREDENTIAL);
        registry.add("inframesh.node.outbound.heartbeat-interval", () -> "200ms");
        registry.add("inframesh.node.outbound.health-interval", () -> "1s");
        registry.add("inframesh.node.outbound.reconnect.initial-delay", () -> "50ms");
        registry.add("inframesh.node.outbound.reconnect.max-delay", () -> "200ms");
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
    void health_isPushedAsNodeHealthResponse_separatelyFromHeartbeat() throws Exception {
        JsonNode health = awaitEnvelope(NodeMessageType.HEALTH);

        assertThat(health.path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        NodeHealthResponse payload = JsonMapper.shared().treeToValue(health.path("payload"), NodeHealthResponse.class);
        assertThat(payload.status()).isEqualTo(NodeStatus.UP);
        assertThat(payload.system().cpu()).isNotNull();
        assertThat(payload.system().memory().total()).isPositive();
        assertThat(payload.runtime().activeRequests()).isNotNull();
        // Same payload as a Worker - Console already knows the node type from the handshake.
        assertThat(health.path("payload").has("nodeType")).isFalse();

        JsonNode heartbeat = awaitEnvelope(NodeMessageType.HEARTBEAT);
        assertThat(heartbeat.path("payload").has("status")).isFalse();
    }

    @Test
    void healthAck_isAcceptedAndReportingContinues() throws Exception {
        String healthMessageId = awaitEnvelope(NodeMessageType.HEALTH).path("messageId").asString();

        CONSOLE.sendTextFrame("{\"messageId\":\"ack-1\",\"type\":\"HEALTH_ACK\",\"nodeId\":\"" + NODE_ID
                + "\",\"requestId\":\"" + healthMessageId + "\"}");

        assertThat(awaitEnvelope(NodeMessageType.HEALTH)).isNotNull();
        assertThat(connection.isConnected()).isTrue();
    }

    @Test
    void reconnect_resumesHealthReporting() throws Exception {
        awaitEnvelope(NodeMessageType.HEALTH);
        drainHandshakes();

        CONSOLE.dropCurrentConnection();

        Map<String, String> reconnect = CONSOLE.awaitHandshakeHeaders(5);
        assertThat(reconnect).isNotNull();
        assertThat(reconnect.get("X-Infra-Node-Id")).isEqualTo(NODE_ID.toString());
        awaitConnected();
        assertThat(awaitEnvelope(NodeMessageType.HEALTH)).isNotNull();
    }

    @Test
    void credential_neverAppearsInHealthPayloadOrLogs() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            JsonNode health = awaitEnvelope(NodeMessageType.HEALTH);
            assertThat(health.toString()).doesNotContain(CREDENTIAL);

            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(CREDENTIAL));
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @Test
    @DirtiesContext
    void shutdown_stopsHealthReporting() throws Exception {
        awaitEnvelope(NodeMessageType.HEALTH);

        ((SmartLifecycle) connection).stop();
        assertThat(connection.isConnected()).isFalse();

        // Drain anything already in flight, then no further HEALTH may arrive over 2+ intervals.
        CONSOLE.awaitTextFrame(1);
        long deadline = System.nanoTime() + Duration.ofMillis(2500).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            assertThat(frame).as("frame after shutdown").isNull();
        }
    }

    private static FakeConsoleServer startConsole() {
        try {
            return new FakeConsoleServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void drainHandshakes() throws InterruptedException {
        while (CONSOLE.awaitHandshakeHeaders(0) != null) {
            // discard
        }
    }

    // HEARTBEATs keep arriving on the same connection, so scan frames until the wanted type shows up.
    // Health collection samples CPU load over ~1s, hence the generous timeout.
    private JsonNode awaitEnvelope(NodeMessageType type) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = CONSOLE.awaitTextFrame(1);
            if (frame == null) {
                continue;
            }
            JsonNode envelope = JsonMapper.shared().readTree(frame);
            if (type.name().equals(envelope.path("type").asString())) {
                return envelope;
            }
        }
        throw new AssertionError("Timed out waiting for " + type);
    }
}
