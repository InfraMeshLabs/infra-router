package koboolean.layarouter.service;

import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.layarouter.config.LayaRouterProperties;
import koboolean.layarouter.config.LayaRouterProperties.FallbackPolicy;
import koboolean.layarouter.config.LayaRouterProperties.ModelCapabilityRule;
import koboolean.layarouter.laya.WorkerTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerSelectorTest {

    private static final String SMALL_MODEL = "gemma3:4b";
    private static final String MEDIUM_MODEL = "qwen3:8b";
    private static final String POWERFUL_MODEL = "qwen3:32b";

    private static final List<ModelCapabilityRule> RULES = List.of(
            new ModelCapabilityRule(SMALL_MODEL, WorkerTier.SMALL),
            new ModelCapabilityRule(MEDIUM_MODEL, WorkerTier.MEDIUM),
            new ModelCapabilityRule(POWERFUL_MODEL, WorkerTier.POWERFUL));

    private static WorkerSelector selector(FallbackPolicy policy, List<ModelCapabilityRule> rules) {
        LayaRouterProperties properties = new LayaRouterProperties();
        properties.setFallbackPolicy(policy);
        properties.setModelCapabilities(rules);
        properties.validate();
        return new WorkerSelector(properties);
    }

    private static WorkerSelector selector(FallbackPolicy policy) {
        return selector(policy, RULES);
    }

    private static WorkerSelector selector() {
        return selector(FallbackPolicy.FALLBACK);
    }

    private static WorkerCandidate candidate(long workerId, String model, String provider, Integer requestCount,
                                             Integer queueSize, Integer latency, Integer gpuUsage, Integer vramUsage,
                                             String gpu, ExecutionLocation location) {
        List<ModelInfo> models = List.of(new ModelInfo(provider, model, location));
        return new WorkerCandidate(workerId, "worker-" + workerId, null, models, provider, null, null,
                requestCount, queueSize, decimal(latency), null, null, null, null, null, null,
                gpu, decimal(gpuUsage), gpu == null ? null : "vram", decimal(vramUsage));
    }

    private static WorkerCandidate candidate(long workerId, String model) {
        return candidate(workerId, model, 0, 0, 0, 0, 0);
    }

    private static WorkerCandidate candidate(long workerId, String model, int requestCount, int queueSize,
                                             int latency, int gpuUsage, int vramUsage) {
        return candidate(workerId, model, "OLLAMA", requestCount, queueSize, latency, gpuUsage, vramUsage,
                "gpu", ExecutionLocation.LOCAL);
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static List<WorkerCandidate> oneOfEach() {
        return List.of(candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL), candidate(3, POWERFUL_MODEL));
    }

    private static void assertUnprocessable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    // --- Tier + Load Score ---------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"SMALL,1", "MEDIUM,2", "POWERFUL,3"})
    void selectsTheWorkerOfTheRequestedTier(WorkerTier tier, long expected) {
        assertThat(selector().select(tier, oneOfEach()).workerId()).isEqualTo(expected);
    }

    @Test
    void picksTheLeastLoadedWorkerAmongTheSameTier() {
        WorkerCandidate high = candidate(1, SMALL_MODEL, 8, 5, 2400, 90, 85);
        WorkerCandidate low = candidate(2, SMALL_MODEL, 3, 1, 900, 50, 60);
        WorkerCandidate medium = candidate(3, SMALL_MODEL, 5, 0, 1200, 65, 70);

        assertThat(selector().select(WorkerTier.SMALL, List.of(high, low, medium)).workerId()).isEqualTo(2L);
    }

    @Test
    void tierTakesPrecedenceOverLoad() {
        WorkerCandidate busyMedium = candidate(1, MEDIUM_MODEL, 9, 9, 5000, 0, 0);
        WorkerCandidate idleSmall = candidate(2, SMALL_MODEL);

        assertThat(selector().select(WorkerTier.MEDIUM, List.of(busyMedium, idleSmall)).workerId()).isEqualTo(1L);
    }

    @Test
    void usesTheClosestMoreCapableTierWhenTheExactTierIsMissing() {
        List<WorkerCandidate> workers = List.of(candidate(1, SMALL_MODEL), candidate(2, POWERFUL_MODEL));

        assertThat(selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, workers).workerId()).isEqualTo(2L);
    }

    @Test
    void smallRequestDoesNotTakeAPowerfulWorkerWhenASmallOneExists() {
        List<WorkerCandidate> workers = List.of(candidate(1, POWERFUL_MODEL), candidate(2, SMALL_MODEL, 5, 5, 0, 0, 0));

        assertThat(selector().select(WorkerTier.SMALL, workers).workerId()).isEqualTo(2L);
    }

    @Test
    void fallbackPolicyUsesTheHighestAvailableTierWhenNoneIsCapableEnough() {
        List<WorkerCandidate> workers = List.of(candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL));

        assertThat(selector(FallbackPolicy.FALLBACK).select(WorkerTier.POWERFUL, workers).workerId()).isEqualTo(2L);
    }

    @Test
    void strictPolicyRejectsWhenNoWorkerIsCapableEnough() {
        List<WorkerCandidate> workers = List.of(candidate(1, SMALL_MODEL), candidate(2, MEDIUM_MODEL));

        assertUnprocessable(() -> selector(FallbackPolicy.STRICT).select(WorkerTier.POWERFUL, workers));
    }

    @Test
    void workerTierIsItsMostCapableModelAndUnknownModelsUseTheDefaultTier() {
        WorkerCandidate multiModel = new WorkerCandidate(1L, "worker-1", null, List.of(
                new ModelInfo("OLLAMA", SMALL_MODEL, ExecutionLocation.LOCAL),
                new ModelInfo("OLLAMA", "QWEN3:32B", ExecutionLocation.LOCAL)), "OLLAMA", null, null,
                0, 0, null, null, null, null, null, null, null, null, null, null, null);
        WorkerCandidate unknown = candidate(2, "some-unlisted-model");

        assertThat(selector(FallbackPolicy.STRICT).select(WorkerTier.POWERFUL, List.of(multiModel, unknown)).workerId())
                .isEqualTo(1L);
        assertUnprocessable(() -> selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, List.of(unknown)));
    }

    @Test
    void aModelIsNotAssignedATierJustBecauseItSharesASizeSuffix() {
        // Only the explicitly mapped model is MEDIUM; another ":8b" model stays at the default tier.
        WorkerCandidate unmapped8b = candidate(1, "some-other-model:8b");

        assertUnprocessable(() -> selector(FallbackPolicy.STRICT).select(WorkerTier.MEDIUM, List.of(unmapped8b)));
    }

    @Test
    void capabilityPatternsMayUseWildcards() {
        List<ModelCapabilityRule> rules = List.of(
                new ModelCapabilityRule("my-finetune-*", WorkerTier.POWERFUL),
                new ModelCapabilityRule("org/model-v?", WorkerTier.MEDIUM));
        WorkerSelector selector = selector(FallbackPolicy.STRICT, rules);

        assertThat(selector.tierOfModel("My-Finetune-v2")).isEqualTo(WorkerTier.POWERFUL);
        assertThat(selector.tierOfModel("org/model-v3")).isEqualTo(WorkerTier.MEDIUM);
        // Everything else is literal: '.' and '/' are not regex/glob syntax here.
        assertThat(selector.tierOfModel("org/model-v10")).isEqualTo(WorkerTier.SMALL);
        assertThat(selector.tierOfModel("orgXmodel-v3")).isEqualTo(WorkerTier.SMALL);
    }

    @Test
    void doesNotFavorACpuWorkerJustBecauseItHasNoGpuMetrics() {
        WorkerCandidate cpuOnly = candidate(1, SMALL_MODEL, "OLLAMA", 5, 5, 2000, null, null, null, ExecutionLocation.LOCAL);
        WorkerCandidate healthyGpu = candidate(2, SMALL_MODEL, 1, 0, 200, 20, 20);

        assertThat(selector().select(WorkerTier.SMALL, List.of(cpuOnly, healthyGpu)).workerId()).isEqualTo(2L);
    }

    @Test
    void breaksIdenticalScoresDeterministicallyByWorkerId() {
        WorkerCandidate a = candidate(5, SMALL_MODEL, 4, 2, 1000, 50, 50);
        WorkerCandidate b = candidate(2, SMALL_MODEL, 4, 2, 1000, 50, 50);

        assertThat(selector().select(WorkerTier.SMALL, List.of(a, b)).workerId()).isEqualTo(2L);
        assertThat(selector().select(WorkerTier.SMALL, List.of(b, a)).workerId()).isEqualTo(2L);
    }

    // --- RoutingHints (same policy as the other Router examples) --------------------------

    @Test
    void rejectsRequestsWithNoWorkerCandidates() {
        assertUnprocessable(() -> selector().filterByHints(null, null));
        assertUnprocessable(() -> selector().filterByHints(List.of(), null));
    }

    @Test
    void filtersOutCandidatesThatDoNotMatchTheRequiredProvider() {
        WorkerCandidate ollama = candidate(1, SMALL_MODEL, "OLLAMA", 0, 0, 0, 0, 0, "gpu", ExecutionLocation.LOCAL);
        WorkerCandidate vllm = candidate(2, SMALL_MODEL, "VLLM", 0, 0, 0, 0, 0, "gpu", ExecutionLocation.LOCAL);

        List<WorkerCandidate> eligible = selector().filterByHints(List.of(ollama, vllm), new RoutingHints(null, "ollama"));

        assertThat(eligible).extracting(WorkerCandidate::workerId).containsExactly(1L);
    }

    @Test
    void rejectsWhenRequiredProviderLeavesNoEligibleWorker() {
        assertUnprocessable(() -> selector().filterByHints(List.of(candidate(1, SMALL_MODEL)), new RoutingHints(null, "VLLM")));
    }

    @Test
    void rejectsWhenGpuRequiredButNoCandidateHasAGpu() {
        WorkerCandidate noGpu = candidate(1, SMALL_MODEL, "OLLAMA", 0, 0, 0, null, null, null, ExecutionLocation.LOCAL);

        assertUnprocessable(() -> selector().filterByHints(List.of(noGpu), hints(true, null)));
    }

    @Test
    void excludesWorkersThatAreNotLocalOnlyWhenPrivateDataIsRequired() {
        WorkerCandidate external = candidate(1, SMALL_MODEL, "OLLAMA", 0, 0, 0, 0, 0, "gpu", ExecutionLocation.EXTERNAL);
        WorkerCandidate unknownLocation = candidate(2, SMALL_MODEL, "OLLAMA", 0, 0, 0, 0, 0, "gpu", null);
        WorkerCandidate local = candidate(3, SMALL_MODEL);

        List<WorkerCandidate> eligible = selector().filterByHints(List.of(external, unknownLocation, local), hints(null, true));

        assertThat(eligible).extracting(WorkerCandidate::workerId).containsExactly(3L);
    }

    @Test
    void rejectsWhenPrivateDataLeavesNoEligibleWorker() {
        WorkerCandidate external = candidate(1, SMALL_MODEL, "OLLAMA", 0, 0, 0, 0, 0, "gpu", ExecutionLocation.EXTERNAL);

        assertUnprocessable(() -> selector().filterByHints(List.of(external), hints(null, true)));
    }

    @Test
    void preferredModelIsASoftPreference() {
        WorkerCandidate small = candidate(1, SMALL_MODEL);
        WorkerCandidate medium = candidate(2, MEDIUM_MODEL);

        List<WorkerCandidate> narrowed = selector().filterByHints(List.of(small, medium), new RoutingHints(MEDIUM_MODEL, null));
        List<WorkerCandidate> unmatched = selector().filterByHints(List.of(small, medium), new RoutingHints("not-offered", null));

        assertThat(narrowed).extracting(WorkerCandidate::workerId).containsExactly(2L);
        assertThat(unmatched).extracting(WorkerCandidate::workerId).containsExactly(1L, 2L);
    }

    @Test
    void rejectsWeightsThatDoNotSumToOne() {
        LayaRouterProperties properties = new LayaRouterProperties();
        properties.getWeights().setLatency(0.5);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("sum to 1.0");
    }

    private static RoutingHints hints(Boolean gpuRequired, Boolean privateData) {
        return new RoutingHints(null, null, null, gpuRequired, null, privateData);
    }
}
