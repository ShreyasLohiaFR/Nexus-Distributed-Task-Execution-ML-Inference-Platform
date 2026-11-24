package com.nexus;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record JobRequest(String kind, JsonNode payload, Integer priority, Integer maxAttempts,
                         Integer delaySeconds) {
    public JobRequest validated() {
        int p = priority == null ? 5 : priority;
        int m = maxAttempts == null ? 3 : maxAttempts;
        int d = delaySeconds == null ? 0 : delaySeconds;
        if (p < 0 || p > 9 || m < 1 || m > 5 || d < 0 || d > 86400) bad("invalid priority, attempts or delay");
        if (kind == null || payload == null || !payload.isObject() || payload.size() != 1) bad("invalid payload");
        switch (kind) {
            case "WORD_COUNT", "SHA256" -> {
                JsonNode text = payload.get("text");
                if (text == null || !text.isTextual() || text.asText().length() > 20000) bad("text must be at most 20000 characters");
            }
            case "DIGIT_CLASSIFY" -> {
                JsonNode pixels = payload.get("pixels");
                if (pixels == null || !pixels.isArray() || pixels.size() != 64) bad("expected 64 pixels");
                for (JsonNode value : pixels) {
                    if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0 || value.asDouble() > 16) bad("pixel values must be between 0 and 16");
                }
            }
            default -> bad("unsupported job kind");
        }
        return new JobRequest(kind, payload, p, m, d);
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
