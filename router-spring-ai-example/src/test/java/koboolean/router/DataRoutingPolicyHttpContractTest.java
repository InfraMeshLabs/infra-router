package koboolean.router;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DIRECT: a DataRoutingPolicy refusal is a 422 whose body carries the same {@code code} as the Laya
 * Routers, and an unknown policy is a 400 that says so. The chat model is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataRoutingPolicyHttpContractTest {

    private static final String EXTERNAL_ONLY = """
            [{"workerId": 2, "provider": "OPENAI",
              "models": [{"provider": "OPENAI", "modelName": "gpt-4o", "executionLocation": "EXTERNAL"}]}]""";

    @LocalServerPort
    private int port;

    @Value("${infra.node.api-key}")
    private String apiKey;

    @MockitoBean
    private ChatModel chatModel;

    @ParameterizedTest
    @CsvSource({
            "'',                                            SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER",
            "'\"dataRoutingPolicy\": \"AUTO\",',            SENSITIVITY_UNVERIFIED_NO_LOCAL_WORKER",
            "'\"dataRoutingPolicy\": \"INTERNAL_ONLY\",',   INTERNAL_ONLY_NO_LOCAL_WORKER",
            "'\"dataRoutingPolicy\": \"EXTERNAL_ALLOWED\", \"routing\": {\"privateData\": true},', PRIVATE_DATA_NO_LOCAL_WORKER",
    })
    void refusesWithTheLayaRoutersCodes(String policy, String code) throws Exception {
        HttpResponse<String> response = post(body(policy, EXTERNAL_ONLY));

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode json = JsonMapper.shared().readTree(response.body());
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("path").asString()).isEqualTo("/api/v1/route");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void externalAllowedMayUseAnExternalWorker() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"workerId\": 2, \"sensitiveData\": false}")))));

        HttpResponse<String> response = post(body("\"dataRoutingPolicy\": \"EXTERNAL_ALLOWED\",", EXTERNAL_ONLY));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.shared().readTree(response.body()).get("workerId").asLong()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOMETIMES", "auto"})
    void rejectsAnUnknownDataRoutingPolicyAsBadRequest(String policy) throws Exception {
        HttpResponse<String> response = post(body("\"dataRoutingPolicy\": \"" + policy + "\",", EXTERNAL_ONLY));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonMapper.shared().readTree(response.body()).get("message").asString())
                .isEqualTo("Malformed RoutingRequest: dataRoutingPolicy must be one of [AUTO, INTERNAL_ONLY, EXTERNAL_ALLOWED].");
    }

    private static String body(String fields, String workers) {
        return "{" + fields + " \"messages\": [{\"role\": \"USER\", \"content\": \"hello\"}], \"workers\": " + workers + "}";
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/route"))
                .header("Content-Type", "application/json")
                .header("X-Infra-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
