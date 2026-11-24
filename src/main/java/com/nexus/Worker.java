package com.nexus;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
@ConditionalOnProperty(name="nexus.worker.enabled", havingValue="true")
public class Worker {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);
    private final String workerId = "worker-" + UUID.randomUUID();
    private final JobStore store;
    private final TaskExecutor tasks;
    private final MeterRegistry metrics;
    private final ExecutorService pool;
    private final int concurrency;
    private final long idlePollMillis;
    private final Map<UUID, UUID> running = new ConcurrentHashMap<>();

    public Worker(JobStore store, TaskExecutor tasks, MeterRegistry metrics,
                  @Value("${nexus.worker.concurrency}") int concurrency,
                  @Value("${nexus.worker.idle-poll-ms:100}") long idlePollMillis) {
        if (concurrency < 1 || concurrency > 32) throw new IllegalArgumentException("worker concurrency must be 1–32");
        this.store = store;
        this.tasks = tasks;
        this.metrics = metrics;
        this.pool = Executors.newFixedThreadPool(concurrency);
        if (idlePollMillis < 25 || idlePollMillis > 1000) throw new IllegalArgumentException("idle poll must be 25–1000 ms");
        this.concurrency = concurrency;
        this.idlePollMillis = idlePollMillis;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < concurrency; i++) pool.submit(this::consume);
    }

    private void consume() {
        while (!pool.isShutdown() && !Thread.currentThread().isInterrupted()) {
            try {
                var next = store.claim(workerId);
                if (next.isPresent()) {
                    var claim = next.get();
                    running.put(claim.job().id(), claim.token());
                    run(claim);
                    // No fixed-delay scheduling gap between completed jobs.
                } else {
                    Thread.sleep(idlePollMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Queue consumer failed; will retry", e);
                try { Thread.sleep(Math.max(idlePollMillis, 250)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @Scheduled(fixedDelay=3000)
    public void maintainLeases() {
        running.forEach((id, token) -> {
            try {
                if (!store.heartbeat(id, token)) running.remove(id, token);
            } catch (RuntimeException e) {
                log.warn("Heartbeat unavailable for job {}", id);
            }
        });
        try {
            int recovered = store.recoverExpired();
            if (recovered > 0) metrics.counter("nexus.jobs.recovered").increment(recovered);
        } catch (RuntimeException e) {
            log.warn("Lease recovery failed; will retry", e);
        }
    }

    private void run(JobStore.Claim claim) {
        UUID id = claim.job().id();
        UUID token = claim.token();
        Timer.Sample start = Timer.start(metrics);
        String outcome = "stale";
        try {
            var result = tasks.execute(claim.job());
            if (store.complete(id, token, result)) outcome = "succeeded";
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                // Avoid saving secrets or raw stack traces in job results.
                if (store.fail(id, token, "Task execution failed: " + e.getClass().getSimpleName())) outcome = "attempt_failed";
            } catch (RuntimeException dbFailure) {
                log.warn("Unable to record failure for {}; lease recovery will handle it", id);
            }
        } finally {
            running.remove(id, token);
            metrics.counter("nexus.jobs.attempts", "kind", claim.job().kind(), "outcome", outcome).increment();
            start.stop(metrics.timer("nexus.job.execution", "kind", claim.job().kind()));
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try { if (!pool.awaitTermination(12, TimeUnit.SECONDS)) pool.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); pool.shutdownNow(); }
    }
}
