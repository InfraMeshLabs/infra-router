package koboolean.jevrouter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Response body from {@code POST /v1/systemone}, scoped to the single Choice question this
 * Router asks. {@code ignoreUnknown} tolerates response fields (e.g. {@code usage}) that
 * this Router doesn't need, so the client stays forward-compatible with the upstream API.
 * See https://docs.typesafe.ai/api.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JevChoiceResponse(
        String model,
        Map<String, ChoiceAnswer> answers
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceAnswer(
            String type,
            String choice,
            Map<String, Double> probabilities,
            Double confidence
    ) {
    }
}
