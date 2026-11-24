package com.petboarding.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.CachingReservationRepository;
import com.petboarding.repository.JsonReservationRepository;
import com.petboarding.repository.ReservationRepository;
import com.petboarding.service.ReservationService;

public class WaitlistPerformanceBenchmark {

    private static final int TOTAL_RECORDS = 5000;
    private static final int WAITLISTED_DOGS = 40;
    private static final int AVAILABILITY_ROUNDS = 300;
    private static final int WAITLIST_CHECKS = 100;
    private static final int PROMOTION_CYCLES = 10;
    private static final int TRIALS = 3;

    public static void main(String[] args) throws IOException {

        String mode = args.length == 0
                ? "baseline"
                : args[0].toLowerCase(Locale.ROOT);

        if (!mode.equals("baseline")
                && !mode.equals("cached")) {

            throw new IllegalArgumentException(
                    "Use baseline or cached.");
        }

        boolean cached = mode.equals("cached");

        System.out.println("Waitlist Performance Benchmark");
        System.out.println("--------------------------------");

        System.out.println("Mode: "
                + (cached ? "Cached JSON repository"
                          : "Baseline JSON repository"));

        System.out.println("Reservation records: " + TOTAL_RECORDS);
        System.out.println("Availability rounds: " + AVAILABILITY_ROUNDS);
        System.out.println("Idle waitlist checks: " + WAITLIST_CHECKS);
        System.out.println("Promotion cycles: " + PROMOTION_CYCLES);
        System.out.println("Trials: " + TRIALS);
        System.out.println("Java version: "
                + System.getProperty("java.version"));

        for (int trial = 1; trial <= TRIALS; trial++) {
            runTrial(trial, cached);
        }

        System.out.println();
        System.out.println("Benchmark completed successfully.");
    }

    // Runs each trial using a fresh temporary JSON file.
    private static void runTrial(int trial, boolean cached)
            throws IOException {

        Path directory = Files.createTempDirectory(
                "petboarding-benchmark-");

        Path file = directory.resolve("reservations.json");

        try {

            JsonReservationRepository jsonRepository =
                    new JsonReservationRepository(file);

            // Generate identical starting data for both modes.
            jsonRepository.save(createDataset());

            // Measure calls to the underlying JSON repository.
            CountingRepository repository =
                    new CountingRepository(jsonRepository);

            // Select cached or uncached repository access.
            ReservationRepository activeRepository = cached
                    ? new CachingReservationRepository(repository)
                    : repository;

            ReservationService service =
                    new ReservationService(activeRepository);

            // Warm up the JVM and repository before measuring.
            for (int i = 0; i < 30; i++) {
                service.getAvailableSpaces(PetType.DOG);
                service.getAvailableSpaces(PetType.CAT);
            }

            for (int i = 0; i < 20; i++) {
                service.promoteWaitlistedReservations();
            }

            repository.resetMeasurements();

            System.out.println();
            System.out.println("Trial " + trial);
            System.out.println("--------------------------------");

            // Measure repeated availability checks.
            long start = System.nanoTime();
            long initialLoads = repository.getLoadCount();

            for (int i = 0; i < AVAILABILITY_ROUNDS; i++) {

                int dogs = service.getAvailableSpaces(PetType.DOG);
                int cats = service.getAvailableSpaces(PetType.CAT);

                require(dogs == 0 && cats == 0,
                        "Unexpected available boarding spaces.");
            }

            double availabilityTime = elapsedMillis(start);

            long availabilityLoads =
                    repository.getLoadCount() - initialLoads;

            // Measure waitlist checks when no space is available.
            start = System.nanoTime();
            initialLoads = repository.getLoadCount();

            for (int i = 0; i < WAITLIST_CHECKS; i++) {

                int promoted =
                        service.promoteWaitlistedReservations();

                require(promoted == 0,
                        "Promotion occurred without available space.");
            }

            double waitlistTime = elapsedMillis(start);

            long waitlistLoads =
                    repository.getLoadCount() - initialLoads;

            // Measure cancellations followed by actual promotions.
            start = System.nanoTime();
            initialLoads = repository.getLoadCount();

            long reservationId = 1001L;

            for (int i = 0; i < PROMOTION_CYCLES; i++) {

                Reservation cancelled =
                        service.cancel(reservationId);

                require(cancelled.getStatus()
                        == ReservationStatus.CANCELLED,
                        "Cancellation failed.");

                int promoted =
                        service.promoteWaitlistedReservations();

                require(promoted == 1,
                        "Expected exactly one promotion.");

                // Cancel the previously promoted dog next time.
                reservationId = 1006L + i;
            }

            double promotionTime = elapsedMillis(start);

            long promotionLoads =
                    repository.getLoadCount() - initialLoads;

            double totalTime = availabilityTime
                    + waitlistTime
                    + promotionTime;

            // Capture measured repository activity.
            long totalLoads = repository.getLoadCount();

            double repositoryLoadTime =
                    repository.getLoadTimeMillis();

            long totalSaves = repository.getSaveCount();

            // Verify final state outside the timed workload.
            verifyFinalState(
                    service.getAllReservations());

            // Independently verify the persistent JSON file.
            verifyFinalState(
                    jsonRepository.load());

            require(totalSaves == PROMOTION_CYCLES * 2L,
                    "Unexpected number of persistent writes.");

            System.out.printf(Locale.ROOT,
                    "Availability total: %.3f ms%n",
                    availabilityTime);

            System.out.printf(Locale.ROOT,
                    "Availability average: %.3f ms per round%n",
                    availabilityTime / AVAILABILITY_ROUNDS);

            System.out.println(
                    "Availability repository loads: "
                    + availabilityLoads);

            System.out.printf(Locale.ROOT,
                    "Idle waitlist total: %.3f ms%n",
                    waitlistTime);

            System.out.printf(Locale.ROOT,
                    "Idle waitlist average: %.3f ms per check%n",
                    waitlistTime / WAITLIST_CHECKS);

            System.out.println(
                    "Idle waitlist repository loads: "
                    + waitlistLoads);

            System.out.printf(Locale.ROOT,
                    "Promotion total: %.3f ms%n",
                    promotionTime);

            System.out.printf(Locale.ROOT,
                    "Promotion average: %.3f ms per cycle%n",
                    promotionTime / PROMOTION_CYCLES);

            System.out.println(
                    "Promotion repository loads: "
                    + promotionLoads);

            System.out.println(
                    "Total JSON repository loads: " + totalLoads);

            System.out.printf(Locale.ROOT,
                    "Total JSON loading time: %.3f ms%n",
                    repositoryLoadTime);

            System.out.println(
                    "Total persistent writes: " + totalSaves);

            System.out.printf(Locale.ROOT,
                    "Total workload time: %.3f ms%n",
                    totalTime);

            System.out.println(
                    "Correctness verification: PASSED");

            System.out.println(
                    "Persistent storage verification: PASSED");

        } finally {

            // Remove generated benchmark data.
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    // Creates a repeatable reservation dataset.
    private static List<Reservation> createDataset() {

        List<Reservation> reservations = new ArrayList<>();

        long id = 1001L;

        // Fill all available dog boarding spaces.
        for (int i = 0; i < 3; i++) {

            reservations.add(new Reservation(
                    id++,
                    "ConfirmedDog" + i,
                    PetType.DOG,
                    ReservationStatus.CONFIRMED));
        }

        // Fill all available cat boarding spaces.
        for (int i = 0; i < 2; i++) {

            reservations.add(new Reservation(
                    id++,
                    "ConfirmedCat" + i,
                    PetType.CAT,
                    ReservationStatus.CONFIRMED));
        }

        // Create a queue of waitlisted dogs.
        for (int i = 0; i < WAITLISTED_DOGS; i++) {

            reservations.add(new Reservation(
                    id++,
                    "WaitlistedDog" + i,
                    PetType.DOG,
                    ReservationStatus.WAITLISTED));
        }

        // Simulate historical cancelled reservations.
        while (reservations.size() < TOTAL_RECORDS) {

            PetType type = id % 2 == 0
                    ? PetType.DOG : PetType.CAT;

            reservations.add(new Reservation(
                    id,
                    "HistoricalPet" + id,
                    type,
                    ReservationStatus.CANCELLED));

            id++;
        }

        return reservations;
    }

    // Verifies capacity, waitlist ordering and record count.
    private static void verifyFinalState(
            List<Reservation> reservations) {

        require(reservations.size() == TOTAL_RECORDS,
                "Unexpected reservation count.");

        long confirmedDogs = reservations.stream()
                .filter(r -> r.getPetType() == PetType.DOG)
                .filter(r -> r.getStatus()
                        == ReservationStatus.CONFIRMED)
                .count();

        long confirmedCats = reservations.stream()
                .filter(r -> r.getPetType() == PetType.CAT)
                .filter(r -> r.getStatus()
                        == ReservationStatus.CONFIRMED)
                .count();

        long waitlistedDogs = reservations.stream()
                .filter(r -> r.getPetType() == PetType.DOG)
                .filter(r -> r.getStatus()
                        == ReservationStatus.WAITLISTED)
                .count();

        long uniqueIds = reservations.stream()
                .map(Reservation::getId)
                .distinct()
                .count();

        require(confirmedDogs == 3,
                "Dog capacity verification failed.");

        require(confirmedCats == 2,
                "Cat capacity verification failed.");

        require(waitlistedDogs
                == WAITLISTED_DOGS - PROMOTION_CYCLES,
                "Unexpected waitlist size.");

        require(uniqueIds == TOTAL_RECORDS,
                "Duplicate reservation IDs detected.");

        // Verify the oldest promoted pets were processed first.
        require(getStatus(reservations, 1001L)
                == ReservationStatus.CANCELLED,
                "Original cancellation was not retained.");

        for (int i = 0; i < WAITLISTED_DOGS; i++) {

            ReservationStatus expected;

            if (i < PROMOTION_CYCLES - 1) {
                expected = ReservationStatus.CANCELLED;
            } else if (i == PROMOTION_CYCLES - 1) {
                expected = ReservationStatus.CONFIRMED;
            } else {
                expected = ReservationStatus.WAITLISTED;
            }

            require(getStatus(reservations, 1006L + i)
                    == expected,
                    "Waitlist ordering verification failed.");
        }
    }

    private static ReservationStatus getStatus(
            List<Reservation> reservations, long id) {

        return reservations.stream()
                .filter(r -> r.getId() == id)
                .findFirst()
                .orElseThrow()
                .getStatus();
    }

    // Stops the benchmark if correctness is compromised.
    private static void require(
            boolean condition, String message) {

        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static double elapsedMillis(long start) {

        return (System.nanoTime() - start) / 1_000_000.0;
    }

    // Measures activity at the underlying persistent repository.
    private static class CountingRepository
            implements ReservationRepository {

        private final ReservationRepository delegate;

        private long loadCount;
        private long loadTimeNanos;
        private long saveCount;

        CountingRepository(ReservationRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Reservation> load() throws IOException {

            long start = System.nanoTime();

            try {
                return delegate.load();
            } finally {
                loadCount++;
                loadTimeNanos += System.nanoTime() - start;
            }
        }

        @Override
        public void save(List<Reservation> reservations)
                throws IOException {

            delegate.save(reservations);

            saveCount++;
        }

        long getLoadCount() {
            return loadCount;
        }

        double getLoadTimeMillis() {
            return loadTimeNanos / 1_000_000.0;
        }

        long getSaveCount() {
            return saveCount;
        }

        void resetMeasurements() {
            loadCount = 0;
            loadTimeNanos = 0;
            saveCount = 0;
        }
    }
}