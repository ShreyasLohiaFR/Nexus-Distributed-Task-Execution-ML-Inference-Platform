package com.nexus;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record Job(UUID id, String kind, JsonNode payload, String state, int priority,
                  int attempts, int maxAttempts, Instant availableAt, Instant createdAt,
                  Instant updatedAt, String workerId, JsonNode result, String error) {}
