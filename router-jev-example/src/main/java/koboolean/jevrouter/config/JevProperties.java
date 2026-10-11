package koboolean.jevrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the Jev (TypeSafe AI) typed-decision API, configured under
 * {@code jev.*} instead of being hardcoded. {@code api-key} must come from the
 * {@code JEV_API_KEY} environment variable - it must never be committed to the repository.
 * See https://docs.typesafe.ai/api for the upstream API reference.
 */
@ConfigurationProperties(prefix = "jev")
public class JevProperties {

    private String baseUrl = "https://api.typesafe.ai";
    private String apiKey;
    private String model = "jev-latest";
    private Duration timeout = Duration.ofSeconds(2);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
