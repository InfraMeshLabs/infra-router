package koboolean.staticrouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.DataRoutingPolicy;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.staticrouter.config.StaticRouterProperties;
import koboolean.staticrouter.config.StaticRouterWeights;
import koboolean.staticrouter.error.RoutingRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static com.inframesh.node.enums.DataRoutingPolicy.AUTO;
import static com.inframesh.node.enums.DataRoutingPolicy.EXTERNAL_ALLOWED;
import static com.inframesh.node.enums.DataRoutingPolicy.INTERNAL_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DataRoutingPolicy and privateData, with the same rule and codes as the Laya Routers. This Router
 * has no sensitivity judge, so AUTO keeps LOCAL-only Workers. In every test the EXTERNAL Worker
 * is the less loaded one, so it wins whenever it is eligible.
 */
class StaticDataRoutingPolicyTest {

    private static final WorkerCandidate LOCAL = worker(1L, 10, local());
    private static final WorkerCandidate EXTERNAL = worker(2L, 0, external());
    private static final WorkerCandidate MIXED = worker(3L, 0, local(), external());
    private static final WorkerCandidate NO_MODELS = worker(4L, 0);

    private final StaticRouterServiceImpl service = new StaticRouterServiceImpl(weights(), new StaticRouterProperties());

    @Test
    void autoUsesLocalWorkers() {
        assertThat(route(AUTO, null, LOCAL)).isEqualTo(1L);                      // 1
        assertThat(route(AUTO, null, LOCAL, EXTERNAL)).isEqualTo(1L);            // 2
    }

    @Test
    void autoRefusesWhenNoLocalOnlyWorkerIsLeft() {
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", EXTERNAL);   // 3
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", MIXED);      // 13
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", NO_MODELS);
    }

    @Test
    void internalOnlyUsesLocalWorkers() {
        assertThat(route(INTERNAL_ONLY, null, LOCAL)).isEqualTo(1L);             // 4
        assertThat(route(INTERNAL_ONLY, null, LOCAL, EXTERNAL, MIXED)).isEqualTo(1L); // 5
    }

    @Test
    void internalOnlyRefusesWhenNoLocalOnlyWorkerIsLeft() {
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", EXTERNAL);   // 6
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", MIXED);      // 14
        assertRefused(INTERNAL_ONLY, null, "INTERNAL_ONLY_NO_LOCAL_WORKER", NO_MODELS);
    }

    @Test
    void externalAllowedKeepsEveryWorkerAndTheLoadScore() {
        assertThat(route(EXTERNAL_ALLOWED, null, EXTERNAL)).isEqualTo(2L);       // 7
        // 8: Load Score 0.30 vs 0.0 + external-penalty 0.1 - the external Worker wins as before.
        assertThat(route(EXTERNAL_ALLOWED, null, LOCAL, EXTERNAL)).isEqualTo(2L);
        assertThat(route(EXTERNAL_ALLOWED, null, MIXED)).isEqualTo(3L);
    }

    @ParameterizedTest
    @EnumSource(DataRoutingPolicy.class)
    void privateDataKeepsLocalWorkersUnderEveryPolicy(DataRoutingPolicy policy) {
        assertThat(route(policy, privateData(), LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);   // 9, 10, 11
        assertRefused(policy, privateData(), "PRIVATE_DATA_NO_LOCAL_WORKER", EXTERNAL);   // 12
    }

    @Test
    void aMissingPolicyIsAuto() {
        RoutingRequest legacy = new RoutingRequest(List.of(ChatMessage.user("hello")), null, null, List.of(LOCAL, EXTERNAL));

        assertThat(legacy.dataRoutingPolicy()).isEqualTo(AUTO);
        assertThat(service.route(legacy).workerId()).isEqualTo(1L);             // 15
    }

    @Test
    void preferredModelNeverBringsBackAnExcludedWorker() {
        RoutingHints preferExternalModel = new RoutingHints("gpt-4o", null);

        assertThat(route(INTERNAL_ONLY, preferExternalModel, LOCAL, EXTERNAL)).isEqualTo(1L);
    }

    @Test
    void aRequiredProviderOnlyExternalWorkersHaveIsRefused() {
        assertRefused(INTERNAL_ONLY, new RoutingHints(null, "OPENAI"), "INTERNAL_ONLY_NO_LOCAL_WORKER", LOCAL, EXTERNAL);
    }

    @Test
    void verifySelectionRejectsAWorkerThatIsNotLocalOnly() {
        assertThatThrownBy(() -> service.verifySelection(EXTERNAL, true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        service.verifySelection(EXTERNAL, false);
        service.verifySelection(LOCAL, true);
    }

    private long route(DataRoutingPolicy policy, RoutingHints hints, WorkerCandidate... workers) {
        return service.route(request(policy, hints, workers)).workerId();
    }

    private void assertRefused(DataRoutingPolicy policy, RoutingHints hints, String code, WorkerCandidate... workers) {
        assertThatThrownBy(() -> service.route(request(policy, hints, workers)))
                .isInstanceOfSatisfying(RoutingRejectedException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.nodeError().code()).isEqualTo(code);
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

    private static WorkerCandidate worker(Long workerId, int requestCount, ModelInfo... models) {
        String provider = models.length > 0 && models[models.length - 1].executionLocation() == ExecutionLocation.EXTERNAL
                ? "OPENAI" : "OLLAMA";
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(models), provider,
                null, null,
                requestCount, 0, BigDecimal.ZERO, null, null,
                null, null, null, null,
                "gpu", BigDecimal.ZERO, "vram", BigDecimal.ZERO);
    }

    private static StaticRouterWeights weights() {
        StaticRouterWeights weights = new StaticRouterWeights();
        weights.setRequestCount(0.30);
        weights.setQueueSize(0.25);
        weights.setLatency(0.20);
        weights.setGpuUsage(0.15);
        weights.setVramUsage(0.10);
        return weights;
    }
}
