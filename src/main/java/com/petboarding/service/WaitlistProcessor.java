package com.petboarding.service;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Runs promotion independently of the HTTP and TCP request threads.
public class WaitlistProcessor implements AutoCloseable {

    private final ReservationService service;
    private final long delayMillis;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(task ->
                    new Thread(task, "waitlist-processor"));
    private boolean started;

    public WaitlistProcessor(ReservationService service, long delayMillis) {
        this.service = Objects.requireNonNull(service);
        if (delayMillis <= 0) {
            throw new IllegalArgumentException("Delay must be positive.");
        }
        this.delayMillis = delayMillis;
    }

    // Check immediately at startup, then wait between completed checks.
    public synchronized void start() {
        if (executor.isShutdown()) {
            throw new IllegalStateException("Processor is closed.");
        }
        if (!started) {
            executor.scheduleWithFixedDelay(this::process, 0,
                    delayMillis, TimeUnit.MILLISECONDS);
            started = true;
        }
    }

    private void process() {
        try {
            service.promoteWaitlistedReservations();
        } catch (IOException | RuntimeException e) {
            // Keep future checks running after a failed repository operation.
            System.err.println("Waitlist processing failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
