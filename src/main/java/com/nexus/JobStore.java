package com.nexus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public static final int LEASE_SECONDS = 15;

    public JobStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Claim(Job job, UUID token) {}
    public record Submission(Job job, boolean created) {}

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private JsonNode parse(String value) throws SQLException {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new SQLException("invalid stored JSON", e); }
    }

    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getString("kind"), parse(rs.getString("payload")),
            rs.getString("state"), rs.getInt("priority"), rs.getInt("attempts"), rs.getInt("max_attempts"),
            rs.getTimestamp("available_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getString("worker_id"),
            parse(rs.getString("result")), rs.getString("error"));
    }

    private final RowMapper<Job> mapper = this::map;

    public Submission submit(JobRequest request, String key) {
        JobRequest r = request.validated();
        if (key != null && (key.isBlank() || key.length() > 100))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid idempotency key");
        String hash = JobRules.sha256(serialize(r));
        List<Job> rows = db.query("""
            INSERT INTO jobs (id,kind,payload,state,priority,max_attempts,idempotency_key,request_hash,available_at)
            VALUES (?, ?, CAST(? AS jsonb), 'QUEUED', ?, ?, ?, ?, now() + (? * interval '1 second'))
            ON CONFLICT (idempotency_key) DO NOTHING RETURNING *
            """, mapper, UUID.randomUUID(), r.kind(), serialize(r.payload()), r.priority(), r.maxAttempts(), key, hash, r.delaySeconds());
        if (!rows.isEmpty()) return new Submission(rows.getFirst(), true);
        String existingHash = db.queryForObject("SELECT request_hash FROM jobs WHERE idempotency_key = ?", String.class, key);
        if (!hash.equals(existingHash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "key already used for a different job");
        return new Submission(db.queryForObject("SELECT * FROM jobs WHERE idempotency_key = ?", mapper, key), false);
    }

    public Optional<Job> find(UUID id) {
        return db.query("SELECT * FROM jobs WHERE id = ?", mapper, id).stream().findFirst();
    }

    public List<Job> recent(String state, int limit) {
        if (state == null || state.isBlank())
            return db.query("SELECT * FROM jobs ORDER BY created_at DESC, id DESC LIMIT ?", mapper, limit);
        return db.query("SELECT * FROM jobs WHERE state = ? ORDER BY created_at DESC, id DESC LIMIT ?", mapper, state, limit);
    }

    public Map<String, Long> counts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String state : List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) counts.put(state, 0L);
        db.query("SELECT state, count(*) AS n FROM jobs GROUP BY state", (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getString("state"), rs.getLong("n")));
        return counts;
    }

    public Optional<Claim> claim(String worker) {
        UUID token = UUID.randomUUID();
        List<Job> rows = db.query("""
            WITH next_job AS (
              SELECT id FROM jobs WHERE state = 'QUEUED' AND available_at <= now() AND attempts < max_attempts
              ORDER BY priority DESC, available_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            ) UPDATE jobs j SET state='RUNNING', attempts=attempts+1, lease_token=?,
              lease_until=now() + (? * interval '1 second'), worker_id=?, updated_at=now(), error=NULL
            FROM next_job n WHERE j.id=n.id RETURNING j.*
            """, mapper, token, LEASE_SECONDS, worker);
        return rows.stream().findFirst().map(j -> new Claim(j, token));
    }

    public boolean heartbeat(UUID id, UUID token) {
        return db.update("""
            UPDATE jobs SET lease_until=now() + (? * interval '1 second'), updated_at=now()
            WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until > now()
            """, LEASE_SECONDS, id, token) == 1;
    }

    public boolean complete(UUID id, UUID token, JsonNode result) {
        return db.update("""
            UPDATE jobs SET state='SUCCEEDED', result=CAST(? AS jsonb), error=NULL,
              lease_token=NULL, lease_until=NULL, updated_at=now()
            WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until > now()
            """, serialize(result), id, token) == 1;
    }

    public boolean fail(UUID id, UUID token, String error) {
        return db.update("""
            UPDATE jobs SET state=CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
              available_at=now() + power(2, attempts) * interval '1 second', error=?,
              lease_token=NULL, lease_until=NULL, updated_at=now()
            WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until > now()
            """, error, id, token) == 1;
    }

    public int recoverExpired() {
        return db.update("""
            UPDATE jobs SET state=CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
              available_at=now() + power(2, attempts) * interval '1 second', error='Worker lease expired',
              lease_token=NULL, lease_until=NULL, updated_at=now()
            WHERE state='RUNNING' AND lease_until <= now()
            """);
    }

    public boolean cancel(UUID id) {
        return db.update("""
            UPDATE jobs SET state='CANCELLED', lease_token=NULL, lease_until=NULL, updated_at=now()
            WHERE id=? AND state IN ('QUEUED','RUNNING')
            """, id) == 1;
    }
}
