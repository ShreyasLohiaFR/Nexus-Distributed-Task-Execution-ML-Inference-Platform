package com.nexus;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobStore store;
    private final Set<String> states = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");

    public JobController(JobStore store) { this.store = store; }

    @PostMapping("/jobs")
    public ResponseEntity<Job> submit(@RequestBody JobRequest request,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        JobStore.Submission submission = store.submit(request, key);
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK).body(submission.job());
    }

    @GetMapping("/jobs")
    public List<Job> list(@RequestParam(required=false) String state,
                          @RequestParam(defaultValue="50") int limit) {
        if (limit < 1 || limit > 200 || (state != null && !states.contains(state)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return store.recent(state, limit);
    }

    @GetMapping("/jobs/{id}")
    public Job get(@PathVariable UUID id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/jobs/{id}/cancel")
    public Job cancel(@PathVariable UUID id) {
        if (!store.cancel(id)) {
            get(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "job is already terminal");
        }
        return get(id);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() { return store.counts(); }
}
