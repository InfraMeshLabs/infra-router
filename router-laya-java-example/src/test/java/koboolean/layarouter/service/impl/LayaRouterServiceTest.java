package koboolean.layarouter.service.impl;

import com.inframesh.node.dto.ChatMessage;
import com.inframesh.node.dto.router.ModelInfo;
import com.inframesh.node.dto.router.RoutingHints;
import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.WorkerCandidate;
import com.inframesh.node.enums.ChatRole;
import com.inframesh.node.enums.ExecutionLocation;
import koboolean.layarouter.config.LayaRouterProperties.ModelCapabilityRule;
import koboolean.layarouter.config.LayaRouterProperties;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
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

    // The real template is free text; these tests pin Laya's input to the message alone.
    private static final ByteArrayResource MESSAGE_ONLY = new ByteArrayResource("{message}".getBytes(StandardCharsets.UTF_8));

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
        service = new LayaRouterServiceImpl(classifier, new PromptBuilder(MESSAGE_ONLY), new WorkerSelector(properties), properties);
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
    void usesAnExternalWorkerOnlyWhenItIsClearlyLessLoaded() {
        when(classifier.classify("hello")).thenReturn(new TierDecision(WorkerTier.MEDIUM, Map.of()));

        long workerId = service.route(request(List.of(ChatMessage.user("hello")), null, List.of(
                worker(1, "qwen3:8b", ExecutionLocation.LOCAL, 10),
                worker(2, "qwen3:8b", ExecutionLocation.EXTERNAL, 0)))).workerId();

        assertThat(workerId).isEqualTo(2L);
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

        assertThat(new PromptBuilder(MESSAGE_ONLY).build(request, List.of())).contains("second question");
    }

    @Test
    void promptFallsBackToTheLastTextWhenNoUserMessageHasContent() {
        RoutingRequest request = request(List.of(
                ChatMessage.user(" "),
                ChatMessage.toolResult("call-1", "tool output")), null, List.of());

        assertThat(new PromptBuilder(MESSAGE_ONLY).build(request, List.of())).contains("tool output");
    }

    @Test
    void promptIsEmptyWhenThereIsNoMessageContent() {
        assertThat(new PromptBuilder(MESSAGE_ONLY).build(request(List.of(), null, List.of()), List.of())).isEmpty();
        assertThat(new PromptBuilder(MESSAGE_ONLY).build(request(null, null, List.of()), List.of())).isEmpty();
    }

    @Test
    void promptFillsTheSameVariablesAsTheSpringAiRouter() {
        PromptBuilder builder = new PromptBuilder(new ByteArrayResource(
                "{messages}|{message}|{preferredModel}|{requiredProvider}|{candidates}".getBytes(StandardCharsets.UTF_8)));
        RoutingRequest request = request(List.of(ChatMessage.system("be brief"), ChatMessage.user("hello")),
                null, List.of());

        String prompt = builder.build(request, List.of(worker(7, "qwen3:8b"))).orElseThrow();

        assertThat(prompt).startsWith("- [SYSTEM] be brief\n- [USER] hello|hello|none|none|- workerId=7, name=");
    }

    private static RoutingRequest request(List<ChatMessage> messages, RoutingHints hints, List<WorkerCandidate> workers) {
        return new RoutingRequest("session-1", messages, null, hints, List.of(), null, workers);
    }

    private static WorkerCandidate worker(long workerId, String model) {
        return worker(workerId, model, ExecutionLocation.LOCAL, 0);
    }

    private static WorkerCandidate worker(long workerId, String model, ExecutionLocation location, int requestCount) {
        return new WorkerCandidate(workerId, "worker-" + workerId, null,
                List.of(new ModelInfo("OLLAMA", model, location)), "OLLAMA", null, null,
                requestCount, 0, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
