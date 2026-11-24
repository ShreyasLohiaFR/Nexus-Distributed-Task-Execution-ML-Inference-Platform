package com.nexus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class TaskExecutor {
    private final ObjectMapper json;
    private final URI mlEndpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public TaskExecutor(ObjectMapper json, @Value("${nexus.ml-url}") String mlUrl) {
        this.json = json;
        this.mlEndpoint = URI.create(mlUrl.replaceAll("/+$", "") + "/predict");
    }

    public JsonNode execute(Job job) throws Exception {
        return switch (job.kind()) {
            case "WORD_COUNT" -> json.valueToTree(Map.of("words", JobRules.wordCount(job.payload().get("text").asText())));
            case "SHA256" -> json.valueToTree(Map.of("sha256", JobRules.sha256(job.payload().get("text").asText())));
            case "DIGIT_CLASSIFY" -> classify(job.payload());
            default -> throw new IllegalArgumentException("unsupported task");
        };
    }

    private JsonNode classify(JsonNode payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(mlEndpoint).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("ML service unavailable");
        JsonNode result = json.readTree(response.body());
        if (!result.has("digit") || !result.get("digit").isIntegralNumber()) throw new IllegalStateException("invalid ML response");
        return result;
    }
}
