package koboolean.jevrouter.client;

import koboolean.jevrouter.config.JevProperties;
import koboolean.jevrouter.dto.JevChoiceRequest;
import koboolean.jevrouter.dto.JevChoiceResponse;
import koboolean.jevrouter.dto.JevChoiceResponse.ChoiceAnswer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Talks to the Jev (TypeSafe AI) HTTP API directly - Jev is not wrapped as a Spring AI
 * {@code ChatModel}, since it is not a chat/completion model but a typed-decision endpoint
 * (see https://docs.typesafe.ai/api). Every failure mode (timeout, connection error,
 * non-2xx, empty/unusable body) is normalized into {@link JevClientException} so the
 * caller has one thing to catch.
 */
@Component
public class JevClient {

    private static final String SYSTEMONE_PATH = "/v1/systemone";
    private static final String WORKER_QUESTION_KEY = "worker";

    private final WebClient webClient;
    private final JevProperties properties;

    public JevClient(WebClient.Builder webClientBuilder, JevProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    public ChoiceAnswer chooseWorker(Object state, String instructions, Map<String, String> criteria) {
        JevChoiceRequest request = new JevChoiceRequest(
                properties.getModel(),
                state,
                Map.of(WORKER_QUESTION_KEY, new JevChoiceRequest.ChoiceQuestion(instructions, criteria)));

        JevChoiceResponse response;
        try {
            response = webClient.post()
                    .uri(SYSTEMONE_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JevChoiceResponse.class)
                    // Reactive-level timeout (cancels the in-flight call), not just an
                    // unblock of the calling thread - Router latency must stay bounded.
                    .timeout(properties.getTimeout())
                    .block();
        } catch (Exception e) {
            // Covers WebClientResponseException (non-2xx), WebClientRequestException
            // (connection failure), the reactive timeout (TimeoutException), and any other
            // unexpected failure - normalized to one exception type for the caller.
            throw new JevClientException("Jev API call failed: " + e.getMessage(), e);
        }

        if (response == null || response.answers() == null) {
            throw new JevClientException("Jev API returned an empty response.");
        }

        ChoiceAnswer answer = response.answers().get(WORKER_QUESTION_KEY);
        if (answer == null || answer.choice() == null) {
            throw new JevClientException("Jev API response did not include a worker choice.");
        }

        return answer;
    }
}
