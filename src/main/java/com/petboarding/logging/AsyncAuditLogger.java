package com.petboarding.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class AsyncAuditLogger implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Path logFile = Path.of("logs", "audit.log");

    // Schedules logging without waiting for the file write.
    public void log(String operation, long reservationId) {

        try {
            executor.submit(() -> {

                String entry = Instant.now()
                        + " | " + operation
                        + " | Reservation ID: " + reservationId
                        + System.lineSeparator();

                try {
                    Files.createDirectories(logFile.getParent());

                    Files.writeString(
                            logFile,
                            entry,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    );

                } catch (IOException e) {
                    System.err.println("Unable to write audit log.");
                }
            });

        } catch (RejectedExecutionException e) {
            System.err.println("Audit logger unavailable.");
        }
    }

    // Allows pending log tasks to finish during shutdown.
    @Override
    public void close() {

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