package com.nexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"nexus.api-key=test-workspace-key-at-least-24-chars", "nexus.worker.enabled=false"})
class JobStoreTest {
    @Autowired JobStore store;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @BeforeEach void reset() {
        assertEquals("nexus_test", db.queryForObject("SELECT current_database()", String.class),
            "Use a disposable database named nexus_test; refusing to delete live jobs");
        db.update("DELETE FROM jobs");
    }

    private JobRequest request(int priority, int attempts, int delay) {
        return new JobRequest("WORD_COUNT", json.createObjectNode().put("text", "alpha beta"), priority, attempts, delay);
    }

    @Test void idempotentSubmissionAndConflict() {
        var first = store.submit(request(5, 3, 0), "same-key");
        var second = store.submit(request(5, 3, 0), "same-key");
        assertTrue(first.created()); assertFalse(second.created());
        assertEquals(first.job().id(), second.job().id());
        assertThrows(ResponseStatusException.class, () -> store.submit(request(7, 3, 0), "same-key"));
    }

    @Test void priorityAndScheduledDelay() {
        var low = store.submit(request(2, 3, 0), null).job();
        var high = store.submit(request(9, 3, 0), null).job();
        store.submit(request(9, 3, 3600), null);
        assertEquals(high.id(), store.claim("w1").orElseThrow().job().id());
        assertEquals(low.id(), store.claim("w2").orElseThrow().job().id());
        assertTrue(store.claim("w3").isEmpty());
    }

    @Test void concurrentClaimsNeverClaimSameJobTwice() throws Exception {
        int jobs = 40;
        for (int i=0;i<jobs;i++) store.submit(request(5, 3, 0), null);
        var calls = new ArrayList<Callable<UUID>>();
        for (int i=0;i<jobs;i++) calls.add(() -> store.claim("worker-"+Thread.currentThread().threadId()).orElseThrow().job().id());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var seen = new HashSet<UUID>();
            for (var future : pool.invokeAll(calls)) assertTrue(seen.add(future.get()));
            assertEquals(jobs, seen.size());
        }
    }

    @Test void expiredLeaseIsRecoveredAndStaleWorkerCannotCommit() {
        var job = store.submit(request(5, 3, 0), null).job();
        var old = store.claim("old-worker").orElseThrow();
        assertTrue(store.heartbeat(job.id(), old.token()));
        db.update("UPDATE jobs SET lease_until=now()-interval '1 second' WHERE id=?", job.id());
        assertFalse(store.complete(job.id(), old.token(), json.createObjectNode()));
        assertEquals(1, store.recoverExpired());
        assertEquals("QUEUED", store.find(job.id()).orElseThrow().state());
        db.update("UPDATE jobs SET available_at=now() WHERE id=?", job.id());
        var newer = store.claim("new-worker").orElseThrow();
        assertNotEquals(old.token(), newer.token());
        assertFalse(store.complete(job.id(), old.token(), json.createObjectNode()));
        assertTrue(store.complete(job.id(), newer.token(), json.createObjectNode().put("words", 2)));
        assertEquals("SUCCEEDED", store.find(job.id()).orElseThrow().state());
    }

    @Test void attemptsExhaustedAndCancellationFence() {
        var job = store.submit(request(5, 1, 0), null).job();
        var c = store.claim("worker").orElseThrow();
        assertTrue(store.fail(job.id(), c.token(), "test failure"));
        assertEquals("FAILED", store.find(job.id()).orElseThrow().state());
        var another = store.submit(request(5, 3, 0), null).job();
        var c2 = store.claim("worker").orElseThrow();
        assertTrue(store.cancel(another.id()));
        assertFalse(store.complete(another.id(), c2.token(), json.createObjectNode()));
        assertEquals("CANCELLED", store.find(another.id()).orElseThrow().state());
    }
}
