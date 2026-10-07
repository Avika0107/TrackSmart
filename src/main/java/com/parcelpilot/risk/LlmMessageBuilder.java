package com.parcelpilot.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelpilot.weather.WeatherSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Optional: rephrases the template message with Anthropic ONLY when
 * ANTHROPIC_API_KEY is set (and app.llm.enabled=true). The LLM receives only the
 * final facts; it never decides the risk level or score. Any failure falls back
 * to the template message.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.llm.enabled", havingValue = "true")
public class LlmMessageBuilder implements MessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(LlmMessageBuilder.class);
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-3-haiku-latest";

    private final TemplateMessageBuilder fallback = new TemplateMessageBuilder();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public LlmMessageBuilder(@Value("${ANTHROPIC_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String build(RiskAssessment a, WeatherSnapshot w) {
        String template = fallback.build(a, w);
        if (apiKey.isBlank()) return template;
        try {
            String prompt = "Rephrase this delivery-update sentence in a friendly, casual tone. "
                    + "Keep every fact exactly as stated (city, numbers, delay estimate). "
                    + "Reply with the sentence only.\n\n" + template;
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "model", MODEL,
                    "max_tokens", 120,
                    "messages", List.of(java.util.Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return template;
            JsonNode text = mapper.readTree(response.body()).path("content").path(0).path("text");
            return text.isMissingNode() || text.asText().isBlank() ? template : text.asText().trim();
        } catch (Exception e) {
            log.warn("LLM rephrase failed; using template message ({})", e.getMessage());
            return template;
        }
    }
}
