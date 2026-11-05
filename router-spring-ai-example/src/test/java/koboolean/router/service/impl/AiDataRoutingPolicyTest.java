package koboolean.router.service.impl;

import com.inframesh.node.connection.NodeErrorCarrier;
import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.DataRoutingPolicy;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.router.error.RoutingRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static com.inframesh.node.enums.DataRoutingPolicy.AUTO;
import static com.inframesh.node.enums.DataRoutingPolicy.EXTERNAL_ALLOWED;
import static com.inframesh.node.enums.DataRoutingPolicy.INTERNAL_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataRoutingPolicy and privateData, with the same rule and codes as the Laya Routers. The model
 * judges {@code sensitiveData} only together with its Worker choice - after the Workers are
 * filtered - so AUTO keeps LOCAL-only Workers. The model answers {@code sensitiveData: false}
 * unless a test says otherwise.
 */
class AiDataRoutingPolicyTest {

    private static final WorkerCandidate LOCAL = worker(1L, local());
    private static final WorkerCandidate EXTERNAL = worker(2L, external());
    private static final WorkerCandidate MIXED = worker(3L, local(), external());
    private static final WorkerCandidate NO_MODELS = worker(4L);

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AiRouterServiceImpl service = new AiRouterServiceImpl(chatModel,
            new ClassPathResource("prompts/routing-system-prompt.st"),
            new ClassPathResource("prompts/routing-user-prompt.st"));

    @Test
    void autoUsesLocalWorkersAndNeverOffersOthersToTheModel() {
        modelPicks(1L, false);

        assertThat(route(AUTO, null, LOCAL)).isEqualTo(1L);                      // 1
        assertThat(route(AUTO, null, LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);     // 2
        assertThat(lastUserPrompt()).contains("workerId=1").doesNotContain("workerId=2").doesNotContain("workerId=3");
    }

    @Test
    void autoRefusesWhenNoLocalOnlyWorkerIsLeftWithoutCallingTheModel() {
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", EXTERNAL);   // 3
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", MIXED);      // 13
        assertRefused(AUTO, null, "SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER", NO_MODELS);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void aModelThatPicksAnExcludedWorkerIsAFailureNotAnExternalRoute() {
        modelPicks(2L, false);

        assertThatThrownBy(() -> route(AUTO, null, LOCAL, EXTERNAL))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void internalOnlyUsesLocalWorkers() {
        modelPicks(1L, false);

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
    void externalAllowedOffersEveryWorkerAndKeepsTheModelsChoice() {
        modelPicks(2L, false);

        assertThat(route(EXTERNAL_ALLOWED, null, EXTERNAL)).isEqualTo(2L);       // 7
        assertThat(route(EXTERNAL_ALLOWED, null, LOCAL, EXTERNAL)).isEqualTo(2L);   // 8
    }

    // The model's own sensitiveData judgement does not narrow what EXTERNAL_ALLOWED allows ...
    @Test
    void externalAllowedDoesNotRefuseOnTheSensitiveDataJudgement() {
        modelPicks(2L, true);

        assertThat(route(EXTERNAL_ALLOWED, null, EXTERNAL)).isEqualTo(2L);
    }

    // ... while privateData still does.
    @Test
    void externalAllowedWithPrivateDataStillRefusesExternalWorkers() {
        assertRefused(EXTERNAL_ALLOWED, privateData(), "PRIVATE_DATA_NO_LOCAL_WORKER", EXTERNAL);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @ParameterizedTest
    @EnumSource(DataRoutingPolicy.class)
    void privateDataKeepsLocalWorkersUnderEveryPolicy(DataRoutingPolicy policy) {
        modelPicks(1L, false);

        assertThat(route(policy, privateData(), LOCAL, EXTERNAL, MIXED)).isEqualTo(1L);   // 9, 10, 11
        assertRefused(policy, privateData(), "PRIVATE_DATA_NO_LOCAL_WORKER", EXTERNAL);   // 12
    }

    @Test
    void aMissingPolicyIsAuto() {
        modelPicks(1L, false);
        RoutingRequest legacy = new RoutingRequest(List.of(ChatMessage.user("hello")), null, null, List.of(LOCAL, EXTERNAL));

        assertThat(legacy.dataRoutingPolicy()).isEqualTo(AUTO);
        assertThat(service.route(legacy).workerId()).isEqualTo(1L);             // 15
        assertThat(lastUserPrompt()).doesNotContain("workerId=2");
    }

    @Test
    void verifySelectionRejectsAWorkerThatIsNotLocalOnly() {
        assertThatThrownBy(() -> service.verifySelection(EXTERNAL, true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        service.verifySelection(EXTERNAL, false);
        service.verifySelection(LOCAL, true);
    }

    private void modelPicks(long workerId, boolean sensitiveData) {
        Generation generation = new Generation(new AssistantMessage(
                "{\"workerId\": " + workerId + ", \"sensitiveData\": " + sensitiveData + "}"));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
    }

    private String lastUserPrompt() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.atLeastOnce()).call(prompt.capture());
        return prompt.getValue().getContents();
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
