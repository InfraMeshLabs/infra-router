package koboolean.staticrouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.staticrouter.config.StaticRouterWeights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticRouterServiceTest {

    private StaticRouterServiceImpl service;

    @BeforeEach
    void setUp() {
        StaticRouterWeights weights = new StaticRouterWeights();
        weights.setRequestCount(0.30);
        weights.setQueueSize(0.25);
        weights.setLatency(0.20);
        weights.setGpuUsage(0.15);
        weights.setVramUsage(0.10);
        service = new StaticRouterServiceImpl(weights);
    }

    @Test
    void picksTheLeastLoadedWorkerAmongHighMediumAndLowLoad() {
        WorkerCandidate high = candidate(1L, 8, 5, 2400, 90, 85);
        WorkerCandidate low = candidate(2L, 3, 1, 900, 50, 60);
        WorkerCandidate medium = candidate(3L, 5, 0, 1200, 65, 70);

        RoutingResponse response = service.route(routingRequest(null, List.of(high, low, medium)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void prefersTheWorkerWithTheEmptierQueueWhenOtherwiseSimilar() {
        WorkerCandidate busyQueue = candidate(1L, 2, 10, 500, 40, 40);
        WorkerCandidate emptyQueue = candidate(2L, 2, 0, 500, 40, 40);

        RoutingResponse response = service.route(routingRequest(null, List.of(busyQueue, emptyQueue)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void prefersTheWorkerWithLowerGpuAndVramUsageWhenOtherwiseSimilar() {
        WorkerCandidate overloaded = candidate(1L, 2, 1, 500, 98, 95);
        WorkerCandidate healthy = candidate(2L, 2, 1, 500, 40, 50);

        RoutingResponse response = service.route(routingRequest(null, List.of(overloaded, healthy)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void prefersTheWorkerWithLowerLatencyWhenOtherwiseSimilar() {
        WorkerCandidate slow = candidate(1L, 2, 1, 5000, 40, 40);
        WorkerCandidate fast = candidate(2L, 2, 1, 500, 40, 40);

        RoutingResponse response = service.route(routingRequest(null, List.of(slow, fast)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void doesNotFavorACpuWorkerJustBecauseItHasNoGpuMetrics() {
        // A CPU-only worker (gpuUsage/vramUsage == null) must not automatically beat a
        // GPU worker that reports low, healthy usage - only real load comparisons should.
        WorkerCandidate cpuOnly = candidateWithoutGpu(1L, 5, 5, 2000);
        WorkerCandidate healthyGpu = candidate(2L, 1, 0, 200, 20, 20);

        RoutingResponse response = service.route(routingRequest(null, List.of(cpuOnly, healthyGpu)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void handlesNullGpuAndVramMetricsWithoutError() {
        WorkerCandidate cpuOnly = candidateWithoutGpu(1L, 2, 1, 500);

        RoutingResponse response = service.route(routingRequest(null, List.of(cpuOnly)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void handlesAllZeroMetricsWithoutDivisionByZeroErrors() {
        WorkerCandidate a = candidate(1L, 0, 0, 0, 0, 0);
        WorkerCandidate b = candidate(2L, 0, 0, 0, 0, 0);

        RoutingResponse response = service.route(routingRequest(null, List.of(a, b)));

        // Every metric ties at 0 - deterministic tie-break falls through to workerId.
        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void breaksIdenticalScoresDeterministicallyByWorkerId() {
        WorkerCandidate a = candidate(5L, 4, 2, 1000, 50, 50);
        WorkerCandidate b = candidate(2L, 4, 2, 1000, 50, 50);

        RoutingResponse first = service.route(routingRequest(null, List.of(a, b)));
        RoutingResponse second = service.route(routingRequest(null, List.of(b, a)));

        assertThat(first.workerId()).isEqualTo(2L);
        assertThat(second.workerId()).isEqualTo(2L);
    }

    @Test
    void rejectsRequestsWithNoWorkerCandidates() {
        RoutingRequest request = routingRequest(null, List.of());

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void rejectsWhenRequiredProviderLeavesNoEligibleWorker() {
        WorkerCandidate ollama = candidate(1L, "OLLAMA", 0, 0, 0, 0, 0);

        RoutingRequest request = routingRequest(new RoutingHints(null, "VLLM"), List.of(ollama));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void filtersOutCandidatesThatDoNotMatchTheRequiredProvider() {
        WorkerCandidate ollama = candidate(1L, "OLLAMA", 5, 5, 2000, 90, 90);
        WorkerCandidate vllm = candidate(2L, "VLLM", 0, 0, 100, 10, 10);

        RoutingResponse response = service.route(
                routingRequest(new RoutingHints(null, "OLLAMA"), List.of(ollama, vllm)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void rejectsWhenGpuRequiredButNoCandidateHasAGpu() {
        WorkerCandidate cpuOnly = candidateWithoutGpu(1L, 0, 0, 100);

        RoutingRequest request = routingRequest(
                new RoutingHints(null, null, List.of(), true, null, null), List.of(cpuOnly));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void rejectsWhenPrivateDataLeavesNoEligibleWorker() {
        WorkerCandidate externalOnly = candidateWithModel(1L, external("OPENAI", "gpt-4o"));

        RoutingRequest request = routingRequest(privateData(), List.of(externalOnly));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void excludesExternalOnlyWorkersWhenPrivateDataIsRequired() {
        WorkerCandidate externalOnly = candidateWithModel(1L, external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidateWithModel(2L, local("OLLAMA", "qwen3"));

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(externalOnly, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    private RoutingHints privateData() {
        return new RoutingHints(null, null, List.of(), null, null, true);
    }

    private ModelInfo local(String provider, String modelName) {
        return new ModelInfo(provider, modelName, ExecutionLocation.LOCAL);
    }

    private ModelInfo external(String provider, String modelName) {
        return new ModelInfo(provider, modelName, ExecutionLocation.EXTERNAL);
    }

    private WorkerCandidate candidate(Long workerId, int requestCount, int queueSize, int latencyMs,
                                       int gpuUsagePercent, int vramUsagePercent) {
        return candidate(workerId, "OLLAMA", requestCount, queueSize, latencyMs, gpuUsagePercent, vramUsagePercent);
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

    private WorkerCandidate candidateWithoutGpu(Long workerId, int requestCount, int queueSize, int latencyMs) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(local("OLLAMA", "qwen3")), "OLLAMA",
                "SPRING_AI", "1.0.0",
                requestCount, queueSize, BigDecimal.valueOf(latencyMs), BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                null, null,
                null, null);
    }

    private WorkerCandidate candidateWithModel(Long workerId, ModelInfo model) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(model), "OLLAMA",
                "SPRING_AI", "1.0.0",
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                "gpu", BigDecimal.ZERO,
                "vram", BigDecimal.ZERO);
    }

    private RoutingRequest routingRequest(RoutingHints hints, List<WorkerCandidate> workers) {
        return new RoutingRequest(List.of(ChatMessage.user("hello")), null, hints, workers);
    }
}
