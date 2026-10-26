package koboolean.layarouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ChatRole;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.layarouter.config.LayaRouterProperties;
import koboolean.layarouter.config.LayaRouterProperties.ModelCapabilityRule;
import koboolean.layarouter.laya.LayaTierClassifier;
import koboolean.layarouter.laya.TierDecision;
import koboolean.layarouter.laya.WorkerTier;
import koboolean.layarouter.service.PromptBuilder;
import koboolean.layarouter.service.WorkerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LayaRouterServiceTest {

    @Mock
    private LayaTierClassifier classifier;

    private LayaRouterServiceImpl service;

    @BeforeEach
    void setUp() {
        LayaRouterProperties properties = new LayaRouterProperties();
        properties.setFallbackPolicy(LayaRouterProperties.FallbackPolicy.STRICT);
        properties.setModelCapabilities(List.of(
                new ModelCapabilityRule("gemma3:4b", WorkerTier.SMALL),
                new ModelCapabilityRule("qwen3:8b", WorkerTier.MEDIUM)));
        service = new LayaRouterServiceImpl(classifier, new PromptBuilder(), new WorkerSelector(properties));
    }

    @Test
    void routesToAWorkerOfTheTierLayaChoseUsingOnlyTheMessageText() {
        when(classifier.classify("hello")).thenReturn(new TierDecision(WorkerTier.MEDIUM, Map.of()));

        long workerId = service.route(request(List.of(ChatMessage.user("hello")), null,
                List.of(worker(1, "gemma3:4b"), worker(2, "qwen3:8b")))).workerId();

        assertThat(workerId).isEqualTo(2L);
    }

    @Test
    void rejectsNoWorkerCandidatesWithoutCallingLaya() {
        assertStatus(() -> service.route(request(List.of(ChatMessage.user("hello")), null, List.of())), 422);
        verify(classifier, never()).classify(any());
    }

    @Test
    void rejectsWhenAHardRoutingHintLeavesNoWorkerWithoutCallingLaya() {
        RoutingRequest request = request(List.of(ChatMessage.user("hello")), new RoutingHints(null, "VLLM"),
                List.of(worker(1, "qwen3:8b")));

        assertStatus(() -> service.route(request), 422);
        verify(classifier, never()).classify(any());
    }

    @Test
    void rejectsARequestWithNoMessageContentWithoutCallingLaya() {
        RoutingRequest request = request(List.of(ChatMessage.user("  ")), null, List.of(worker(1, "qwen3:8b")));

        assertStatus(() -> service.route(request), 422);
        verify(classifier, never()).classify(any());
    }

    @Test
    void propagatesALayaFailureAsBadGateway() {
        when(classifier.classify(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        assertStatus(() -> service.route(request(List.of(ChatMessage.user("hello")), null,
                List.of(worker(1, "qwen3:8b")))), 502);
    }

    // --- PromptBuilder ------------------------------------------------------------------

    @Test
    void promptIsTheLastUserMessage() {
        RoutingRequest request = request(List.of(
                ChatMessage.system("You are helpful."),
                ChatMessage.user("first question"),
                ChatMessage.assistant("an answer"),
                ChatMessage.user("  second question  "),
                new ChatMessage(ChatRole.ASSISTANT, null)), null, List.of());

        assertThat(new PromptBuilder().build(request)).contains("second question");
    }

    @Test
    void promptFallsBackToTheLastTextWhenNoUserMessageHasContent() {
        RoutingRequest request = request(List.of(
                ChatMessage.user(" "),
                ChatMessage.toolResult("call-1", "tool output")), null, List.of());

        assertThat(new PromptBuilder().build(request)).contains("tool output");
    }

    @Test
    void promptIsEmptyWhenThereIsNoMessageContent() {
        assertThat(new PromptBuilder().build(request(List.of(), null, List.of()))).isEmpty();
        assertThat(new PromptBuilder().build(request(null, null, List.of()))).isEmpty();
    }

    private static RoutingRequest request(List<ChatMessage> messages, RoutingHints hints, List<WorkerCandidate> workers) {
        return new RoutingRequest("session-1", messages, null, hints, List.of(), null, workers);
    }

    private static WorkerCandidate worker(long workerId, String model) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null,
                List.of(new ModelInfo("OLLAMA", model, ExecutionLocation.LOCAL)), "OLLAMA", null, null,
                0, 0, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
