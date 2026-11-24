package com.nexus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"nexus.api-key=test-workspace-key-at-least-24-chars", "nexus.worker.enabled=false"})
@AutoConfigureMockMvc
class JobApiTest {
    private static final String KEY = "test-workspace-key-at-least-24-chars";
    private static final String PAYLOAD = """
        {"kind":"WORD_COUNT","payload":{"text":"alpha beta"},"delaySeconds":3600}
        """;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    @org.junit.jupiter.api.BeforeEach void requireTestDatabase() {
        org.junit.jupiter.api.Assertions.assertEquals("nexus_test", db.queryForObject("select current_database()", String.class), "Use only a dedicated test database");
    }

    @Test void workspaceRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/stats").servletPath("/api/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats").servletPath("/api/stats").header("X-API-Key","incorrect"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats").servletPath("/api/stats").header("X-API-Key",KEY))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
    }

    @Test void publicAssetsAndMetricsBoundary() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").servletPath("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats;ignored=x").servletPath("/api/stats;ignored=x")).andExpect(status().isUnauthorized());
    }

    @Test void invalidKindAndOversizedBodyAreRejected() throws Exception {
        mvc.perform(post("/api/jobs").servletPath("/api/jobs").header("X-API-Key",KEY)
            .contentType("application/json").content("{\"kind\":\"BAD\",\"payload\":{\"text\":\"x\"}}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/jobs").servletPath("/api/jobs").header("X-API-Key",KEY)
            .contentType("application/json").content("x".repeat(131073)))
            .andExpect(status().isPayloadTooLarge());
    }

    @Test void repeatedSubmissionAndConflictingKey() throws Exception {
        String idempotency = UUID.randomUUID().toString();
        mvc.perform(post("/api/jobs").servletPath("/api/jobs").header("X-API-Key",KEY)
            .header("Idempotency-Key",idempotency).contentType("application/json").content(PAYLOAD))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("QUEUED"));
        mvc.perform(post("/api/jobs").servletPath("/api/jobs").header("X-API-Key",KEY)
            .header("Idempotency-Key",idempotency).contentType("application/json").content(PAYLOAD))
            .andExpect(status().isOk());
        mvc.perform(post("/api/jobs").servletPath("/api/jobs").header("X-API-Key",KEY)
            .header("Idempotency-Key",idempotency).contentType("application/json").content(PAYLOAD.replace("alpha beta","other")))
            .andExpect(status().isConflict());
    }
}
