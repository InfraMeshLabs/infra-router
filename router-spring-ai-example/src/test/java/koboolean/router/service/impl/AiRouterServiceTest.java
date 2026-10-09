package koboolean.router.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ExecutionLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRouterServiceTest {

    @Mock
    private ChatModel chatModel;

    private AiRouterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiRouterServiceImpl(
                chatModel,
                new ClassPathResource("prompts/routing-system-prompt.st"),
                new ClassPathResource("prompts/routing-user-prompt.st")
        );
    }

    @Test
    void trustsTheModelWhenItPicksACandidateThatWasActuallyOffered() {
        WorkerCandidate worker1 = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate worker2 = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("here you go: {\"workerId\": 2} thanks");

        RoutingResponse response = service.route(routingRequest(null, List.of(worker1, worker2)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void rejectsWithBadGatewayWhenTheModelPicksAWorkerNotInTheCandidateList() {
        WorkerCandidate worker1 = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate worker2 = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 999}");

        RoutingRequest request = routingRequest(null, List.of(worker1, worker2));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void rejectsWithBadGatewayWhenTheModelCallFails() {
        WorkerCandidate worker1 = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate worker2 = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model unavailable"));

        RoutingRequest request = routingRequest(null, List.of(worker1, worker2));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void rejectsWithBadGatewayWhenTheModelResponseIsNotParsableJson() {
        WorkerCandidate worker1 = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate worker2 = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("I think worker 2 is best.");

        RoutingRequest request = routingRequest(null, List.of(worker1, worker2));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void neverOffersACandidateThatViolatesTheRequiredProviderToTheModel() {
        WorkerCandidate ollama1 = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate ollama2 = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate vllm = candidate(3L, "VLLM", local("VLLM", "qwen3"));
        respondWith("{\"workerId\": 1}");

        RoutingResponse response = service.route(
                routingRequest(new RoutingHints(null, "OLLAMA"), List.of(ollama1, ollama2, vllm)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void rejectsRequiredProviderThatNoCandidateSatisfies() {
        WorkerCandidate ollama = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));

        RoutingRequest request = routingRequest(new RoutingHints(null, "VLLM"), List.of(ollama));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void rejectsRequestsWithNoWorkerCandidates() {
        RoutingRequest request = routingRequest(null, List.of());

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void filtersOutCandidatesWithoutAGpuWhenGpuRequired() {
        WorkerCandidate withGpu = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"));
        WorkerCandidate withoutGpu = candidateWithoutGpu(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 1}");

        RoutingResponse response = service.route(
                routingRequest(new RoutingHints(null, null, List.of(), true, null, null),
                        List.of(withGpu, withoutGpu)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void rejectsWhenGpuRequiredButNoCandidateHasAGpu() {
        WorkerCandidate withoutGpu = candidateWithoutGpu(1L, "OLLAMA", local("OLLAMA", "qwen3"));

        RoutingRequest request = routingRequest(
                new RoutingHints(null, null, List.of(), true, null, null), List.of(withoutGpu));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    // --- privateData: allowed candidates -----------------------------------------

    @Test
    void keepsALocalOnlyWorkerWhenPrivateDataIsRequired() {
        WorkerCandidate localOnly = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"), local("OLLAMA", "llama3"));
        respondWith("{\"workerId\": 1}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(localOnly)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    // --- privateData: excluded candidates -----------------------------------------

    @Test
    void excludesAnExternalOnlyWorkerWhenPrivateDataIsRequired() {
        WorkerCandidate externalOnly = candidate(1L, "OLLAMA", external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 2}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(externalOnly, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void excludesTheWholeWorkerWhenAnyOfItsModelsIsExternal() {
        // Worker A: Qwen -> LOCAL, GPT -> EXTERNAL. Even though it also has a LOCAL
        // model, the Router cannot force which model the Worker picks internally, so
        // the whole Worker must be excluded - not just the EXTERNAL model.
        WorkerCandidate mixed = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"), external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "llama3"));
        respondWith("{\"workerId\": 2}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(mixed, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void excludesAMixedWorkerEvenIfTheModelTriesToPickIt() {
        // Proves the mixed worker never even reaches the model: if it were offered,
        // the model naming it would be accepted (it's a real candidate id), but here
        // it is rejected as "not among the offered candidates".
        WorkerCandidate mixed = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"), external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "llama3"));
        respondWith("{\"workerId\": 1}");

        RoutingRequest request = routingRequest(privateData(), List.of(mixed, localOnly));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void excludesAWorkerWithNoModelsWhenPrivateDataIsRequired() {
        WorkerCandidate noModels = candidateWithModels(1L, "OLLAMA", null);
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 2}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(noModels, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void excludesAWorkerWithAnEmptyModelListWhenPrivateDataIsRequired() {
        WorkerCandidate noModels = candidateWithModels(1L, "OLLAMA", List.of());
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 2}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(noModels, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    @Test
    void excludesAWorkerWithAnUnknownExecutionLocationWhenPrivateDataIsRequired() {
        WorkerCandidate unknownLocation = candidate(1L, "OLLAMA", new ModelInfo("OLLAMA", "qwen3", null));
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"));
        respondWith("{\"workerId\": 2}");

        RoutingResponse response = service.route(routingRequest(privateData(), List.of(unknownLocation, localOnly)));

        assertThat(response.workerId()).isEqualTo(2L);
    }

    // --- privateData: not requested -------------------------------------------------

    @Test
    void keepsAnExternalWorkerWhenPrivateDataIsFalse() {
        WorkerCandidate externalOnly = candidate(1L, "OLLAMA", external("OPENAI", "gpt-4o"));
        respondWith("{\"workerId\": 1}");

        RoutingHints hints = new RoutingHints(null, null, List.of(), null, null, false);
        RoutingResponse response = service.route(routingRequest(hints, List.of(externalOnly)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void keepsAnExternalWorkerWhenPrivateDataIsNull() {
        WorkerCandidate externalOnly = candidate(1L, "OLLAMA", external("OPENAI", "gpt-4o"));
        respondWith("{\"workerId\": 1}");

        RoutingHints hints = new RoutingHints(null, null, List.of(), null, null, null);
        RoutingResponse response = service.route(routingRequest(hints, List.of(externalOnly)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    @Test
    void keepsAnExternalWorkerWhenRoutingHintsAreAbsent() {
        WorkerCandidate externalOnly = candidate(1L, "OLLAMA", external("OPENAI", "gpt-4o"));
        respondWith("{\"workerId\": 1}");

        RoutingResponse response = service.route(routingRequest(null, List.of(externalOnly)));

        assertThat(response.workerId()).isEqualTo(1L);
    }

    // --- privateData: failure -------------------------------------------------------

    @Test
    void rejectsWhenPrivateDataLeavesNoEligibleWorker() {
        WorkerCandidate externalOnly = candidate(1L, "OLLAMA", external("OPENAI", "gpt-4o"));
        WorkerCandidate mixed = candidate(2L, "OLLAMA", local("OLLAMA", "qwen3"), external("OPENAI", "gpt-4o"));

        RoutingRequest request = routingRequest(privateData(), List.of(externalOnly, mixed));

        assertThatThrownBy(() -> service.route(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // --- privateData: never reaches the AI selector ----------------------------------

    @Test
    void neverOffersAnExternalCapableWorkerToTheModelWhenPrivateDataIsRequired() {
        WorkerCandidate mixed = candidate(1L, "OLLAMA", local("OLLAMA", "qwen3"), external("OPENAI", "gpt-4o"));
        WorkerCandidate localOnly = candidate(2L, "OLLAMA", local("OLLAMA", "llama3"));
        respondWith("{\"workerId\": 2}");

        service.route(routingRequest(privateData(), List.of(mixed, localOnly)));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        String userPrompt = promptCaptor.getValue().getContents();
        assertThat(userPrompt).doesNotContain("workerId=1").contains("workerId=2");
    }

    private void respondWith(String text) {
        Generation generation = new Generation(new AssistantMessage(text));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
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

    private WorkerCandidate candidate(Long workerId, String provider, ModelInfo... models) {
        return candidateWithModels(workerId, provider, List.of(models));
    }

    private WorkerCandidate candidateWithModels(Long workerId, String provider, List<ModelInfo> models) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, models, provider,
                "SPRING_AI", "1.0.0",
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                "gpu", BigDecimal.ZERO,
                "vram", BigDecimal.ZERO);
    }

    private WorkerCandidate candidateWithoutGpu(Long workerId, String provider, ModelInfo... models) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null, List.of(models), provider,
                "SPRING_AI", "1.0.0",
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0L,
                "cpu", BigDecimal.ZERO,
                "memory", BigDecimal.ZERO,
                null, null,
                null, null);
    }

    private RoutingRequest routingRequest(RoutingHints hints, List<WorkerCandidate> workers) {
        return new RoutingRequest(List.of(ChatMessage.user("hello")), null, hints, workers);
    }
}
