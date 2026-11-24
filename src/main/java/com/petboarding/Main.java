package com.petboarding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.petboarding.api.ReservationHttpServer;
import com.petboarding.logging.AsyncAuditLogger;
import com.petboarding.network.TcpServer;
import com.petboarding.repository.CachingReservationRepository;
import com.petboarding.repository.CsvReservationRepository;
import com.petboarding.repository.JsonReservationRepository;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.repository.ReservationRepository;
import com.petboarding.service.ReservationService;
import com.petboarding.service.WaitlistProcessor;

public class Main {

    public static void main(String[] args) throws IOException {

        // Read the configured API key.
        String apiKey =
                System.getenv("PET_BOARDING_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println(
                    "Missing PET_BOARDING_API_KEY.");

            return;
        }

        // Determine the active storage format.
        String storage = System.getenv()
                .getOrDefault("PET_BOARDING_STORAGE", "csv")
                .trim()
                .toLowerCase(Locale.ROOT);

        // Allow caching to be disabled for performance comparisons.
        String cacheOption = System.getenv()
                .getOrDefault("PET_BOARDING_CACHE", "true")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!cacheOption.equals("true")
                && !cacheOption.equals("false")) {

            System.err.println(
                    "PET_BOARDING_CACHE must be true or false.");

            return;
        }

        boolean cacheEnabled =
                Boolean.parseBoolean(cacheOption);

        ReservationRepository repository;

        switch (storage) {

            case "csv":

                repository = new CsvReservationRepository(
                        Path.of("data", "reservations.csv"));
                break;

            case "json":

                repository = new JsonReservationRepository(
                        Path.of("data", "reservations.json"));
                break;

            case "memory":

                repository =
                        new InMemoryReservationRepository();
                break;

            default:

                System.err.println(
                        "Invalid storage option: " + storage);
                return;
        }

        // Apply the verified optimization to JSON storage.
        boolean cachingActive =
                cacheEnabled && storage.equals("json");

        if (cachingActive) {

            repository =
                    new CachingReservationRepository(repository);
        }

        // One shared asynchronous audit logger.
        AsyncAuditLogger auditLogger =
                new AsyncAuditLogger();

        // One shared reservation service.
        ReservationService service =
                new ReservationService(
                        repository, auditLogger);

        // Both interfaces share the same service.
        ReservationHttpServer httpServer =
                new ReservationHttpServer(
                        service, 8082, apiKey);

        TcpServer tcpServer =
                new TcpServer(
                        service, 5050, apiKey);

        System.out.println(
                "Pet Boarding Reservation System");

        System.out.println(
                "--------------------------------");

        System.out.println(
                "Storage format: " + storage);

        System.out.println(
                "Repository caching: "
                        + (cachingActive ? "enabled" : "disabled"));

        // Start one background processor for the shared service.
        WaitlistProcessor waitlistProcessor =
                new WaitlistProcessor(service, 250);

        // Stop network requests and promotion
        // before closing the audit logger.
        AtomicBoolean shuttingDown = new AtomicBoolean();

        Runnable shutdown = () -> {

            if (shuttingDown.compareAndSet(false, true)) {

                tcpServer.stop();
                httpServer.stop();
                waitlistProcessor.close();
                auditLogger.close();
            }
        };

        Runtime.getRuntime().addShutdownHook(
                new Thread(shutdown));

        try {

            waitlistProcessor.start();
            httpServer.start();
            tcpServer.start();

        } finally {

            shutdown.run();
        }
    }
}