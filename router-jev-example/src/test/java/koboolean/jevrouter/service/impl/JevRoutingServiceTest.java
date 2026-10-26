package koboolean.jevrouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.jevrouter.client.JevClient;
import koboolean.jevrouter.client.JevClientException;
import koboolean.jevrouter.dto.JevChoiceResponse.ChoiceAnswer;
import koboolean.jevrouter.dto.JevWorkerState;
import koboolean.jevrouter.error.RoutingRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JevRoutingServiceTest {

    @Mock
    private JevClient jevClient;

    private JevRoutingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new JevRoutingServiceImpl(jevClient,
                new ClassPathResource("prompts/worker-choice-instructions.txt"));
    }

    @Test
    void trustsJevWhenItPicksACandidateThatWasActuallyOffered() {
        WorkerCandidate a = candidate(1L, "OLLAMA", 5, 5, 2000, 90, 90);
        WorkerCandidate b = candidate(2L, "OLLAMA", 1, 0, 200, 20, 20);
        respondWith("worker_2", 0.91);

        RoutingResponse response = service.route(routingRequest(null, List.of(a, b)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void fallsBackToDeterministicSelectionWhenJevPicksAWorkerNotInTheCandidateList() {
        WorkerCandidate a = candidate(1L, "OLLAMA", 5, 5, 2000, 90, 90);
        WorkerCandidate b = candidate(2L, "OLLAMA", 1, 0, 200, 20, 20);
        respondWith("worker_999", 0.5);

        RoutingResponse response = service.route(routingRequest(null, List.of(a, b)));

        // Fallback picks the lowest queueSize/currentRequestCount/latency - worker 2 here.
        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void fallsBackToDeterministicSelectionWhenTheJevCallTimesOut() {
        WorkerCandidate busy = candidate(1L, "OLLAMA", 5, 10, 2000, 90, 90);
        WorkerCandidate idle = candidate(2L, "OLLAMA", 0, 0, 100, 10, 10);
        when(jevClient.chooseWorker(any(), any(), any()))
                .thenThrow(new JevClientException("Jev API call failed: timeout"));

        RoutingResponse response = service.route(routingRequest(null, List.of(busy, idle)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void fallsBackToDeterministicSelectionOn5xxAndDoesNotPropagateTheFailure() {
        WorkerCandidate busy = candidate(1L, "OLLAMA", 5, 10, 2000, 90, 90);
        WorkerCandidate idle = candidate(2L, "OLLAMA", 0, 0, 100, 10, 10);
        when(jevClient.chooseWorker(any(), any(), any()))
                .thenThrow(new JevClientException("Jev API returned an error status: 500 INTERNAL_SERVER_ERROR"));

        RoutingResponse response = service.route(routingRequest(null, List.of(busy, idle)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void tellsJevWhereEachWorkerRunsItsModels() {
        WorkerCandidate localOnly = candidateWithModel(1L, local("OLLAMA", "qwen3"));
        WorkerCandidate externalOnly = candidateWithModel(2L, external("OPENAI", "gpt-4o"));
        WorkerCandidate unknown = candidateWithModel(3L, new ModelInfo("OLLAMA", "qwen3", null));
        respondWith("worker_1", 0.9);

        service.route(routingRequest(null, List.of(localOnly, externalOnly, unknown)));

        verify(jevClient).chooseWorker(
                argThat(state -> state instanceof List<?> list && list.stream()
                        .map(worker -> ((JevWorkerState) worker).executionLocation())
                        .toList().equals(List.of("LOCAL", "EXTERNAL", "UNKNOWN"))),
                any(),
                argThat(criteria -> criteria.get("worker_2").contains("executionLocation=EXTERNAL")));
    }

    @Test
    void fallbackPrefersALocalWorkerOverALessLoadedExternalOne() {
        WorkerCandidate busyLocal = candidateWithModel(1L, local("OLLAMA", "qwen3"), 5, 3);
        WorkerCandidate idleExternal = candidateWithModel(2L, external("OPENAI", "gpt-4o"), 0, 0);
        when(jevClient.chooseWorker(any(), any(), any())).thenThrow(new JevClientException("timeout"));

        RoutingResponse response = service.route(routingRequest(null, List.of(busyLocal, idleExternal)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void neverOffersACandidateThatViolatesTheRequiredProviderToJev() {
        WorkerCandidate ollama = candidate(1L, "OLLAMA", 0, 0, 100, 10, 10);
        WorkerCandidate vllm = candidate(2L, "VLLM", 0, 0, 100, 10, 10);
        respondWith("worker_1", 0.8);

        service.route(routingRequest(new RoutingHints(null, "OLLAMA"), List.of(ollama, vllm)));

        verify(jevClient).chooseWorker(any(), any(), argThatCriteriaHasOnly("worker_1"));
    }

    @Test
    void rejectsWhenRequiredProviderLeavesNoEligibleWorkerAndNeverCallsJev() {
        WorkerCandidate ollama = candidate(1L, "OLLAMA", 0, 0, 100, 10, 10);

        RoutingRequest request = routingRequest(new RoutingHints(null, "VLLM"), List.of(ollama));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        verify(jevClient, never()).chooseWorker(any(), any(), any());
    }

    @Test
    void rejectsRequestsWithNoWorkerCandidatesAndNeverCallsJev() {
        RoutingRequest request = routingRequest(null, List.of());

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        verify(jevClient, never()).chooseWorker(any(), any(), any());
    }

    @Test
    void excludesExternalOnlyWorkersWhenPrivateDataIsRequiredAndNeverOffersThemToJev() {
        WorkerCandidate externalOnly = candidateWithModel(1L, external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidateWithModel(2L, local("OLLAMA", "qwen3"));
        respondWith("worker_2", 0.7);

        RoutingHints privateData = new RoutingHints(null, null, List.of(), null, null, true);
        RoutingResponse response = service.route(routingRequest(privateData, List.of(externalOnly, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
        verify(jevClient).chooseWorker(any(), any(), argThatCriteriaHasOnly("worker_2"));
    }

    @Test
    void rejectsWhenPrivateDataLeavesNoEligibleWorkerAndNeverCallsJev() {
        WorkerCandidate externalOnly = candidateWithModel(1L, external("OPENAI", "gpt-4o"));
        RoutingHints privateData = new RoutingHints(null, null, List.of(), null, null, true);

        RoutingRequest request = routingRequest(privateData, List.of(externalOnly));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(RoutingRejectedException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("PRIVATE_DATA_NO_LOCAL_WORKER");
                    assertThat(e.nodeError().code()).isEqualTo("PRIVATE_DATA_NO_LOCAL_WORKER");
                });
        verify(jevClient, never()).chooseWorker(any(), any(), any());
    }

    private void respondWith(String choice, double confidence) {
        when(jevClient.chooseWorker(any(), any(), any()))
                .thenReturn(new ChoiceAnswer("choice", choice, Map.of(choice, confidence), confidence));
    }

    private Map<String, String> argThatCriteriaHasOnly(String... expectedKeys) {
        return org.mockito.ArgumentMatchers.<Map<String, String>>argThat(criteria ->
                criteria != null && criteria.keySet().equals(Set.of(expectedKeys)));
    }

    private ModelInfo local(String provider, String modelName) {
        return new ModelInfo(provider, modelName, ExecutionLocation.LOCAL);
    }

    private ModelInfo external(String provider, String modelName) {
        return new ModelInfo(provider, modelName, ExecutionLocation.EXTERNAL);
    }

    private WorkerCandidate candidate(Long workerId, String provider, int requestCount, int queueSize,
                                       int latencyMs, int gpuUsagePercent, int vramUsagePercent) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(local(provider, "qwen3")), provider,
                "SPRING_AI", "1.0.0",
                requestCount, queueSize, BigDecimal.valueOf(latencyMs), BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                "gpu", BigDecimal.valueOf(gpuUsagePercent),
                "vram", BigDecimal.valueOf(vramUsagePercent));
    }

    private WorkerCandidate candidateWithModel(Long workerId, ModelInfo model) {
        return candidateWithModel(workerId, model, 0, 0);
    }

    private WorkerCandidate candidateWithModel(Long workerId, ModelInfo model, int requestCount, int queueSize) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(model), "OLLAMA",
                "SPRING_AI", "1.0.0",
                requestCount, queueSize, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                "gpu", BigDecimal.ZERO,
                "vram", BigDecimal.ZERO);
    }

    private RoutingRequest routingRequest(RoutingHints hints, List<WorkerCandidate> workers) {
        return new RoutingRequest(List.of(ChatMessage.user("hello")), null, hints, workers);
    }
}
