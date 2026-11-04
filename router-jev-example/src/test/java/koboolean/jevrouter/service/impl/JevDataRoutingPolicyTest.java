package koboolean.jevrouter.service.impl;

import com.inframesh.node.connection.NodeErrorCarrier;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.DataRoutingPolicy;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.jevrouter.client.JevClient;
import koboolean.jevrouter.client.JevClientException;
import koboolean.jevrouter.dto.JevChoiceResponse.ChoiceAnswer;
import koboolean.jevrouter.error.RoutingRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.inframesh.node.enums.DataRoutingPolicy.AUTO;
import static com.inframesh.node.enums.DataRoutingPolicy.EXTERNAL_ALLOWED;
import static com.inframesh.node.enums.DataRoutingPolicy.INTERNAL_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataRoutingPolicy and privateData, with the same rule and codes as the Laya Routers. This Router
 * has no sensitivity judge, so AUTO keeps LOCAL-only Workers. Jev always answers with the EXTERNAL
 * Worker here: it is honoured only when that Worker was offered, otherwise the fallback picks
 * among the Workers that were.
 */
class JevDataRoutingPolicyTest {

    private static final WorkerCandidate LOCAL = worker(1L, local());
    private static final WorkerCandidate EXTERNAL = worker(2L, external());
    private static final WorkerCandidate MIXED = worker(3L, local(), external());
    private static final WorkerCandidate NO_MODELS = worker(4L);

    private final JevClient jevClient = mock(JevClient.class);
    private final JevRoutingServiceImpl service =
            new JevRoutingServiceImpl(jevClient, new ClassPathResource("prompts/worker-choice-instructions.txt"));

    @Test
    void autoUsesLocalWorkersAndNeverOffersOthersToJev() {
        jevPicks("worker_2");

        assertThat(route(AUTO, null, LOCAL)).isEqualTo(1L);                      // 1
        assertThat(route(AUTO, null, LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);     // 2
        verify(jevClient, never()).chooseWorker(any(), any(), argThat(criteria -> !criteria.keySet().equals(Set.of("worker_1"))));
    }

    @Test
    void autoRefusesWhenNoLocalOnlyWorkerIsLeftWithoutCallingJev() {
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", EXTERNAL);   // 3
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", MIXED);      // 13
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", NO_MODELS);
        verify(jevClient, never()).chooseWorker(any(), any(), any());
    }

    @Test
    void internalOnlyUsesLocalWorkers() {
        jevPicks("worker_2");

        assertThat(route(INTERNAL_ONLY, null, LOCAL)).isEqualTo(1L);             // 4
        assertThat(route(INTERNAL_ONLY, null, LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);   // 5
    }

    @Test
    void internalOnlyRefusesWhenNoLocalOnlyWorkerIsLeft() {
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", EXTERNAL);   // 6
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", MIXED);      // 14
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", NO_MODELS);
    }

    @Test
    void externalAllowedOffersEveryWorkerAndKeepsJevsChoice() {
        jevPicks("worker_2");

        assertThat(route(EXTERNAL_ALLOWED, null, EXTERNAL)).isEqualTo(2L);       // 7
        assertThat(route(EXTERNAL_ALLOWED, null, LOCAL, EXTERNAL)).isEqualTo(2L);   // 8
    }

    @Test
    void externalAllowedKeepsTheFallbackOrder() {
        when(jevClient.chooseWorker(any(), any(), any())).thenThrow(new JevClientException("timeout"));

        // The fallback still prefers the LOCAL-only Worker, as before.
        assertThat(route(EXTERNAL_ALLOWED, null, EXTERNAL, LOCAL)).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(DataRoutingPolicy.class)
    void privateDataKeepsLocalWorkersUnderEveryPolicy(DataRoutingPolicy policy) {
        jevPicks("worker_2");

        assertThat(route(policy, privateData(), LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);   // 9, 10, 11
        assertRefused(policy, privateData(), "PRIVATE_DATA_NO_LOCAL_WORKER", EXTERNAL);   // 12
    }

    @Test
    void aMissingPolicyIsAuto() {
        jevPicks("worker_2");
        RoutingRequest legacy = new RoutingRequest(List.of(ChatMessage.user("hello")), null, null, List.of(LOCAL, EXTERNAL));

        assertThat(legacy.dataRoutingPolicy()).isEqualTo(AUTO);
        assertThat(service.route(legacy).workerId()).isEqualTo(1L);             // 15
    }

    @Test
    void preferredModelNeverBringsBackAnExcludedWorker() {
        jevPicks("worker_2");

        assertThat(route(INTERNAL_ONLY, new RoutingHints("gpt-4o", null), LOCAL, EXTERNAL)).isEqualTo(1L);
    }

    @Test
    void verifySelectionRejectsAWorkerThatIsNotLocalOnly() {
        assertThatThrownBy(() -> service.verifySelection(EXTERNAL, true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        service.verifySelection(EXTERNAL, false);
        service.verifySelection(LOCAL, true);
    }

    private void jevPicks(String choice) {
        when(jevClient.chooseWorker(any(), any(), any()))
                .thenReturn(new ChoiceAnswer("choice", choice, Map.of(choice, 0.9), 0.9));
    }

    private long route(DataRoutingPolicy policy, RoutingHints hints, WorkerCandidate... workers) {
        return service.route(request(policy, hints, workers)).workerId();
    }

    // DIRECT renders getCode() in the 422 body; OUTBOUND sends what the SDK derives from the
    // exception - both must be the code.
    private void assertRefused(DataRoutingPolicy policy, RoutingHints hints, String code, WorkerCandidate... workers) {
        assertThatThrownBy(() -> service.route(request(policy, hints, workers)))
                .isInstanceOfSatisfying(RoutingRejectedException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(NodeErrorCarrier.toNodeError(e).code()).isEqualTo(code);
                });
    }

    private static RoutingRequest request(DataRoutingPolicy policy, RoutingHints hints, WorkerCandidate... workers) {
        return new RoutingRequest(null, List.of(ChatMessage.user("hello")), null, hints, List.of(), null,
                List.of(workers), null, null, policy);
    }

    private static RoutingHints privateData() {
        return new RoutingHints(null, null, List.of(), null, null, true);
    }

    private static ModelInfo local() {
        return new ModelInfo("OLLAMA", "qwen3", ExecutionLocation.LOCAL);
    }

    private static ModelInfo external() {
        return new ModelInfo("OPENAI", "gpt-4o", ExecutionLocation.EXTERNAL);
    }

    private static WorkerCandidate worker(Long workerId, ModelInfo... models) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(models), "OLLAMA",
                null, null,
                0, 0, BigDecimal.ZERO, null, null,
                null, null, null, null,
                "gpu", BigDecimal.ZERO, "vram", BigDecimal.ZERO);
    }
}
