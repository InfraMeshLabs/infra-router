package koboolean.layarouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.DataRoutingPolicy;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.layarouter.config.LayaRouterProperties;
import koboolean.layarouter.config.LayaRouterProperties.FallbackPolicy;
import koboolean.layarouter.config.LayaRouterProperties.ModelCapabilityRule;
import koboolean.layarouter.error.RoutingRejectedException;
import koboolean.layarouter.laya.LayaTierClassifier;
import koboolean.layarouter.laya.TierDecision;
import koboolean.layarouter.laya.WorkerTier;
import koboolean.layarouter.policy.DataRoutingGuard;
import koboolean.layarouter.policy.SensitivityChecker;
import koboolean.layarouter.policy.SensitivityVerdict;
import koboolean.layarouter.service.PromptBuilder;
import koboolean.layarouter.service.WorkerSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static koboolean.layarouter.error.RoutingRejectedException.INTERNAL_ONLY_NO_LOCAL_WORKER;
import static koboolean.layarouter.error.RoutingRejectedException.PRIVATE_DATA_NO_LOCAL_WORKER;
import static koboolean.layarouter.error.RoutingRejectedException.SENSITIVE_DATA_NO_LOCAL_WORKER;
import static koboolean.layarouter.error.RoutingRejectedException.SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataRoutingPolicy and privateData restrict the candidates; Laya's tier and the Worker selection
 * then run unchanged. In every test the EXTERNAL Worker is the less loaded one, so it is picked
 * whenever it is allowed.
 */
@ExtendWith(OutputCaptureExtension.class)
class DataRoutingPolicyTest {

    private static final String SECRET = "my api key is sk-test-1234";

    private static final WorkerCandidate LOCAL = worker(1, "qwen3:8b", ExecutionLocation.LOCAL, 10, null);
    private static final WorkerCandidate EXTERNAL = worker(2, "qwen3:8b", ExecutionLocation.EXTERNAL, 0, null);

    private final LayaTierClassifier classifier = mock(LayaTierClassifier.class);
    private final AtomicInteger sensitivityChecks = new AtomicInteger();

    // --- AUTO ----------------------------------------------------------------------------

    @Test
    void autoKeepsASensitiveRequestOnLocalWorkers() {
        assertThat(route(verdict(SensitivityVerdict.SENSITIVE), DataRoutingPolicy.AUTO, null, LOCAL, EXTERNAL)).isEqualTo(1L);
    }

    @Test
    void autoAllowsExternalWorkersForARequestJudgedNotSensitive() {
        assertThat(route(verdict(SensitivityVerdict.NOT_SENSITIVE), DataRoutingPolicy.AUTO, null, LOCAL, EXTERNAL)).isEqualTo(2L);
    }

    @Test
    void autoFailsClosedWhenTheSensitivityCheckIsUncertainOrGivesNoVerdict() {
        assertThat(route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.AUTO, null, LOCAL, EXTERNAL)).isEqualTo(1L);
        assertThat(route(verdict(null), DataRoutingPolicy.AUTO, null, LOCAL, EXTERNAL)).isEqualTo(1L);
    }

    @Test
    void autoFailsClosedWhenTheSensitivityCheckFailsAndNeverLogsTheContent(CapturedOutput output) {
        SensitivityChecker failing = request -> {
            throw new IllegalStateException("timed out judging: " + SECRET);
        };

        assertThat(route(failing, DataRoutingPolicy.AUTO, null, LOCAL, EXTERNAL)).isEqualTo(1L);
        assertThat(output).contains("event=sensitivity_check_failed").doesNotContain(SECRET);
    }

    @Test
    void aMissingPolicyIsAuto() {
        RoutingRequest legacy = new RoutingRequest("session-1", List.of(ChatMessage.user(SECRET)), null, null,
                List.of(), null, List.of(LOCAL, EXTERNAL));

        assertThat(legacy.dataRoutingPolicy()).isEqualTo(DataRoutingPolicy.AUTO);
        when(classifier.classify(any())).thenReturn(new TierDecision(WorkerTier.MEDIUM, Map.of()));
        assertThat(service(verdict(SensitivityVerdict.UNKNOWN)).route(legacy).workerId()).isEqualTo(1L);
    }

    @Test
    void autoRefusesWithoutFallingBackToExternalWorkersWhenNoLocalWorkerIsLeft() {
        assertRefused(() -> route(verdict(SensitivityVerdict.SENSITIVE), DataRoutingPolicy.AUTO, null, EXTERNAL),
                SENSITIVE_DATA_NO_LOCAL_WORKER);
        assertRefused(() -> route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.AUTO, null, EXTERNAL),
                SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER);
        verify(classifier, never()).classify(any());
    }

    // --- INTERNAL_ONLY / EXTERNAL_ALLOWED -------------------------------------------------

    @Test
    void internalOnlyUsesOnlyLocalWorkersWithoutJudgingTheContent() {
        assertThat(route(verdict(SensitivityVerdict.NOT_SENSITIVE), DataRoutingPolicy.INTERNAL_ONLY, null, LOCAL, EXTERNAL))
                .isEqualTo(1L);
        assertThat(sensitivityChecks).hasValue(0);
    }

    @Test
    void internalOnlyRefusesWhenNoLocalWorkerIsLeft() {
        assertRefused(() -> route(verdict(SensitivityVerdict.NOT_SENSITIVE), DataRoutingPolicy.INTERNAL_ONLY, null, EXTERNAL),
                INTERNAL_ONLY_NO_LOCAL_WORKER);
        verify(classifier, never()).classify(any());
    }

    @Test
    void externalAllowedUsesLocalAndExternalWorkersWithoutJudgingTheContent() {
        assertThat(route(verdict(SensitivityVerdict.SENSITIVE), DataRoutingPolicy.EXTERNAL_ALLOWED, null, LOCAL, EXTERNAL))
                .isEqualTo(2L);
        assertThat(route(verdict(SensitivityVerdict.SENSITIVE), DataRoutingPolicy.EXTERNAL_ALLOWED, null, LOCAL))
                .isEqualTo(1L);
        assertThat(sensitivityChecks).hasValue(0);
    }

    // --- privateData wins under every policy ----------------------------------------------

    @ParameterizedTest
    @EnumSource(DataRoutingPolicy.class)
    void privateDataKeepsTheRequestOnLocalWorkersUnderEveryPolicy(DataRoutingPolicy policy) {
        RoutingHints privateData = new RoutingHints(null, null, null, null, null, true);

        assertThat(route(verdict(SensitivityVerdict.NOT_SENSITIVE), policy, privateData, LOCAL, EXTERNAL)).isEqualTo(1L);
        assertRefused(() -> route(verdict(SensitivityVerdict.NOT_SENSITIVE), policy, privateData, EXTERNAL),
                PRIVATE_DATA_NO_LOCAL_WORKER);
        assertThat(sensitivityChecks).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(DataRoutingPolicy.class)
    void privateDataFalseLeavesThePolicyInCharge(DataRoutingPolicy policy) {
        RoutingHints notPrivate = new RoutingHints(null, null, null, null, null, false);

        long expected = policy == DataRoutingPolicy.EXTERNAL_ALLOWED ? 2L : 1L;
        assertThat(route(verdict(SensitivityVerdict.UNKNOWN), policy, notPrivate, LOCAL, EXTERNAL)).isEqualTo(expected);
    }

    // --- The rest of the selection runs on the restricted candidates ----------------------

    @Test
    void layaTierAndLoadScoreChooseAmongTheLocalWorkersOnly() {
        WorkerCandidate localSmall = worker(1, "gemma3:4b", ExecutionLocation.LOCAL, 0, null);
        WorkerCandidate localMediumBusy = worker(2, "qwen3:8b", ExecutionLocation.LOCAL, 10, null);
        WorkerCandidate localMediumIdle = worker(3, "qwen3:8b", ExecutionLocation.LOCAL, 5, null);
        WorkerCandidate externalMediumIdle = worker(4, "qwen3:8b", ExecutionLocation.EXTERNAL, 0, null);

        assertThat(route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.INTERNAL_ONLY, null,
                localSmall, localMediumBusy, localMediumIdle, externalMediumIdle)).isEqualTo(3L);
    }

    @Test
    void tierFallbackNeverBringsBackAnExcludedExternalWorker() {
        WorkerCandidate localSmall = worker(1, "gemma3:4b", ExecutionLocation.LOCAL, 0, null);
        WorkerCandidate externalPowerful = worker(2, "qwen3:32b", ExecutionLocation.EXTERNAL, 0, null);
        when(classifier.classify(any())).thenReturn(new TierDecision(WorkerTier.POWERFUL, Map.of()));

        long selected = service(verdict(SensitivityVerdict.UNKNOWN), FallbackPolicy.FALLBACK)
                .route(request(DataRoutingPolicy.INTERNAL_ONLY, null, localSmall, externalPowerful)).workerId();

        assertThat(selected).isEqualTo(1L);
    }

    @Test
    void preferredModelNeverBringsBackAnExcludedExternalWorker() {
        WorkerCandidate externalPreferred = worker(2, "gpt-4o", ExecutionLocation.EXTERNAL, 0, null);

        assertThat(route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.INTERNAL_ONLY,
                new RoutingHints("gpt-4o", null), LOCAL, externalPreferred)).isEqualTo(1L);
    }

    @Test
    void aRequiredProviderOrGpuOnlyExternalWorkersHaveIsRefusedNotSentOut() {
        WorkerCandidate localOllama = worker(1, "qwen3:8b", ExecutionLocation.LOCAL, 0, null);
        WorkerCandidate externalOpenAiWithGpu = new WorkerCandidate(2L, "worker-2", null,
                List.of(new ModelInfo("OPENAI", "qwen3:8b", ExecutionLocation.EXTERNAL)), "OPENAI", null, null,
                0, 0, null, null, null, null, null, null, null, "H100", null, null, null);
        RoutingHints openAi = new RoutingHints(null, "OPENAI");
        RoutingHints gpu = new RoutingHints(null, null, null, true, null, null);

        assertRefused(() -> route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.INTERNAL_ONLY, openAi,
                localOllama, externalOpenAiWithGpu), INTERNAL_ONLY_NO_LOCAL_WORKER);
        assertRefused(() -> route(verdict(SensitivityVerdict.UNKNOWN), DataRoutingPolicy.AUTO, gpu,
                localOllama, externalOpenAiWithGpu), SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER);
    }

    // --- helpers -------------------------------------------------------------------------

    private SensitivityChecker verdict(SensitivityVerdict verdict) {
        return request -> {
            sensitivityChecks.incrementAndGet();
            return verdict;
        };
    }

    private long route(SensitivityChecker checker, DataRoutingPolicy policy, RoutingHints hints, WorkerCandidate... workers) {
        when(classifier.classify(any())).thenReturn(new TierDecision(WorkerTier.MEDIUM, Map.of()));
        return service(checker).route(request(policy, hints, workers)).workerId();
    }

    private LayaRouterServiceImpl service(SensitivityChecker checker) {
        return service(checker, FallbackPolicy.STRICT);
    }

    private LayaRouterServiceImpl service(SensitivityChecker checker, FallbackPolicy fallbackPolicy) {
        LayaRouterProperties properties = new LayaRouterProperties();
        properties.setFallbackPolicy(fallbackPolicy);
        properties.setModelCapabilities(List.of(
                new ModelCapabilityRule("gemma3:4b", WorkerTier.SMALL),
                new ModelCapabilityRule("qwen3:8b", WorkerTier.MEDIUM),
                new ModelCapabilityRule("qwen3:32b", WorkerTier.POWERFUL),
                new ModelCapabilityRule("gpt-4o", WorkerTier.MEDIUM)));
        return new LayaRouterServiceImpl(classifier,
                new PromptBuilder(new ByteArrayResource("{message}".getBytes(StandardCharsets.UTF_8))),
                new WorkerSelector(properties), new DataRoutingGuard(checker), properties);
    }

    private static RoutingRequest request(DataRoutingPolicy policy, RoutingHints hints, WorkerCandidate... workers) {
        return new RoutingRequest("session-1", List.of(ChatMessage.user(SECRET)), null, hints, List.of(), null,
                List.of(workers), null, null, policy);
    }

    private static WorkerCandidate worker(long workerId, String model, ExecutionLocation location, int requestCount, String gpu) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null,
                List.of(new ModelInfo("OLLAMA", model, location)), "OLLAMA", null, null,
                requestCount, 0, null, null, null, null, null, null, null, gpu, null, null, null);
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RoutingRejectedException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(422);
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.nodeError().code()).isEqualTo(code);
            assertThat(e.getReason()).doesNotContain(SECRET);
        });
    }
}
