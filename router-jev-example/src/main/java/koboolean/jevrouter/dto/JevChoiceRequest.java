package koboolean.jevrouter.dto;

import java.util.Map;

/**
 * Request body for {@code POST /v1/systemone}. This is the Jev (TypeSafe AI) wire format,
 * not an InfraMesh Routing DTO - it must never be confused with, or used in place of,
 * {@code com.inframesh.node.dto.router.RoutingRequest}. See https://docs.typesafe.ai/api.
 */
public record JevChoiceRequest(
        String model,
        Object state,
        Map<String, ChoiceQuestion> questions
) {

    public record ChoiceQuestion(
            String type,
            String instructions,
            Map<String, String> criteria
    ) {
        public ChoiceQuestion(String instructions, Map<String, String> criteria) {
            this("choice", instructions, criteria);
        }
    }
}
