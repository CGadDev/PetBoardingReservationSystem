package com.petboarding.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.CachingReservationRepository;
import com.petboarding.repository.CsvReservationRepository;
import com.petboarding.repository.ReservationRepository;
import com.petboarding.service.ReservationService;

public class PerformanceBenchmark {

    private static final int[] DATASET_SIZES = {
            10000
    };

    private static final int CHECKS = 10000;

    public static void main(String[] args)
            throws IOException, InterruptedException {

        // Allow time to connect VisualVM before benchmarking.
        System.out.println("Benchmark starts in 15 seconds...");
        Thread.sleep(15000);

        System.out.println("Pet Boarding Performance Benchmark");
        System.out.println("----------------------------------");

        for (int size : DATASET_SIZES) {
            runBenchmark(size);
        }
    }

    private static void runBenchmark(int size)
            throws IOException {

        // Create a temporary CSV file.
        Path file = Files.createTempFile(
                "petboarding-benchmark-", ".csv");

        try {
            CsvReservationRepository csvRepository =
                    new CsvReservationRepository(file);

            List<Reservation> reservations =
                    new ArrayList<>();

            // Generate historical cancelled reservations.
            for (int i = 0; i < size; i++) {

                reservations.add(new Reservation(
                        i + 1001,
                        "Pet" + i,
                        i % 2 == 0 ? PetType.DOG : PetType.CAT,
                        ReservationStatus.CANCELLED
                ));
            }

            // Save the generated dataset.
            csvRepository.save(reservations);

            System.out.println("Dataset size: " + size);
            System.out.println("Availability checks: " + CHECKS);
            System.out.println();

            // Measure the original CSV implementation.
            System.out.println("ORIGINAL IMPLEMENTATION");

            ReservationService originalService =
                    new ReservationService(csvRepository);

            measurePerformance(originalService);

            System.out.println();

            // Measure the optimized caching implementation.
            System.out.println("OPTIMIZED IMPLEMENTATION");

            ReservationRepository cachedRepository =
                    new CachingReservationRepository(
                            new CsvReservationRepository(file));

            ReservationService optimizedService =
                    new ReservationService(cachedRepository);

            measurePerformance(optimizedService);

            System.out.println();

        } finally {

            // Remove the temporary CSV file.
            Files.deleteIfExists(file);
        }
    }

    private static void measurePerformance(
            ReservationService service)
            throws IOException {

        // Warm up the application before measuring.
        for (int i = 0; i < 5; i++) {

            service.getAvailableSpaces(PetType.DOG);
        }

        // Begin measuring execution time.
        long start = System.nanoTime();

        for (int i = 0; i < CHECKS; i++) {

            int available =
                    service.getAvailableSpaces(PetType.DOG);

            // Verify the correct availability result.
            if (available != 3) {

                throw new IllegalStateException(
                        "Unexpected availability result.");
            }
        }

        // Stop measuring execution time.
        long end = System.nanoTime();

        double milliseconds =
                (end - start) / 1_000_000.0;

        double average =
                milliseconds / CHECKS;

        System.out.printf(
                "Execution time: %.3f ms%n",
                milliseconds);

        System.out.printf(
                "Average per check: %.3f ms%n",
                average);
    }
}