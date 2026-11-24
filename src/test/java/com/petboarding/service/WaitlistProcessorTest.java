package com.petboarding.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.CsvReservationRepository;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.repository.JsonReservationRepository;
import com.petboarding.repository.ReservationRepository;

public class WaitlistProcessorTest {

    @TempDir
    Path tempDir;

    private ReservationStatus statusOf(ReservationService service, long id)
            throws IOException {
        return service.getAllReservations().stream()
                .filter(r -> r.getId() == id)
                .findFirst().orElseThrow().getStatus();
    }

    private Reservation fillDogs(ReservationService service) throws IOException {
        Reservation first = service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);
        return first;
    }

    // Poll for observable state with a bounded timeout, not an exact sleep.
    private void awaitPromotion(ReservationService service, long id)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (statusOf(service, id) != ReservationStatus.CONFIRMED) {
            if (System.nanoTime() >= deadline) {
                fail("Waitlisted reservation was not promoted within five seconds.");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void testOldestEligibleReservationPromotedFirst() throws Exception {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        Reservation first = fillDogs(service);
        Reservation oldest = service.reserve("Charlie", PetType.DOG);
        Reservation newer = service.reserve("Daisy", PetType.DOG);
        service.cancel(first.getId());

        assertEquals(1, service.promoteWaitlistedReservations());
        assertEquals(ReservationStatus.CONFIRMED, statusOf(service, oldest.getId()));
        assertEquals(ReservationStatus.WAITLISTED, statusOf(service, newer.getId()));
        assertEquals(0, service.getAvailableSpaces(PetType.DOG));
        assertEquals(0, service.promoteWaitlistedReservations());
    }

    @Test
    void testCancelledWaitlistEntrySkippedAndMultipleSpacesFilled() throws Exception {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        Reservation first = fillDogs(service);
        long secondId = service.getAllReservations().get(1).getId();
        Reservation cancelled = service.reserve("Charlie", PetType.DOG);
        Reservation next = service.reserve("Daisy", PetType.DOG);
        Reservation last = service.reserve("Luna", PetType.DOG);
        service.cancel(cancelled.getId());
        service.cancel(first.getId());
        service.cancel(secondId);

        assertEquals(2, service.promoteWaitlistedReservations());
        assertEquals(ReservationStatus.CANCELLED, statusOf(service, cancelled.getId()));
        assertEquals(ReservationStatus.CONFIRMED, statusOf(service, next.getId()));
        assertEquals(ReservationStatus.CONFIRMED, statusOf(service, last.getId()));
        assertEquals(0, service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testPetTypeCapacityRemainsIndependent() throws Exception {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        Reservation first = fillDogs(service);
        service.reserve("Milo", PetType.CAT);
        service.reserve("Whiskers", PetType.CAT);
        Reservation cat = service.reserve("Kitty", PetType.CAT);
        Reservation dog = service.reserve("Charlie", PetType.DOG);
        service.cancel(first.getId());

        assertEquals(1, service.promoteWaitlistedReservations());
        assertEquals(ReservationStatus.CONFIRMED, statusOf(service, dog.getId()));
        assertEquals(ReservationStatus.WAITLISTED, statusOf(service, cat.getId()));
    }

    @Test
    void testBackgroundPromotionAfterCancellation() throws Exception {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        Reservation first = fillDogs(service);
        Reservation waiting = service.reserve("Charlie", PetType.DOG);
        try (WaitlistProcessor worker = new WaitlistProcessor(service, 20)) {
            worker.start();
            service.cancel(first.getId());
            awaitPromotion(service, waiting.getId());
            assertEquals(0, service.getAvailableSpaces(PetType.DOG));
        }
    }

    @Test
    void testConcurrentPromotionAndNewReservation() throws Exception {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        Reservation first = fillDogs(service);
        Reservation waiting = service.reserve("Charlie", PetType.DOG);
        service.cancel(first.getId());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = executor.submit(() -> {
                start.await();
                return service.promoteWaitlistedReservations();
            });
            Future<Integer> b = executor.submit(() -> {
                start.await();
                return service.promoteWaitlistedReservations();
            });
            Future<Reservation> c = executor.submit(() -> {
                start.await();
                return service.reserve("Daisy", PetType.DOG);
            });
            start.countDown();
            assertEquals(1, a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS));
            assertEquals(ReservationStatus.WAITLISTED, c.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(ReservationStatus.CONFIRMED, statusOf(service, waiting.getId()));
            assertEquals(0, service.getAvailableSpaces(PetType.DOG));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void testStartupRecoversPendingPromotion(String format) throws Exception {
        Path file = tempDir.resolve("reservations." + format);
        ReservationService original = new ReservationService(repository(format, file));
        Reservation first = fillDogs(original);
        Reservation waiting = original.reserve("Charlie", PetType.DOG);
        original.cancel(first.getId());

        ReservationService restored = new ReservationService(repository(format, file));
        assertEquals(ReservationStatus.WAITLISTED, statusOf(restored, waiting.getId()));
        try (WaitlistProcessor worker = new WaitlistProcessor(restored, 20)) {
            worker.start();
            awaitPromotion(restored, waiting.getId());
        }
        ReservationService reloaded = new ReservationService(repository(format, file));
        assertEquals(ReservationStatus.CONFIRMED, statusOf(reloaded, waiting.getId()));
        assertEquals(0, reloaded.getAvailableSpaces(PetType.DOG));
    }

    private ReservationRepository repository(String format, Path file) {
        return format.equals("csv") ? new CsvReservationRepository(file)
                : new JsonReservationRepository(file);
    }

    @Test
    void testFailedSaveLeavesWaitlistIntactAndWorkerRetries() throws Exception {
        InMemoryReservationRepository backing = new InMemoryReservationRepository();
        AtomicBoolean failNextSave = new AtomicBoolean();
        ReservationRepository repository = new ReservationRepository() {
            public List<Reservation> load() { return backing.load(); }
            public void save(List<Reservation> reservations) throws IOException {
                if (failNextSave.getAndSet(false)) {
                    throw new IOException("Simulated write failure");
                }
                backing.save(reservations);
            }
        };
        ReservationService service = new ReservationService(repository);
        Reservation first = fillDogs(service);
        Reservation waiting = service.reserve("Charlie", PetType.DOG);
        service.cancel(first.getId());

        failNextSave.set(true);
        assertThrows(IOException.class, service::promoteWaitlistedReservations);
        assertEquals(ReservationStatus.WAITLISTED, statusOf(service, waiting.getId()));
        assertEquals(1, service.getAvailableSpaces(PetType.DOG));

        failNextSave.set(true);
        try (WaitlistProcessor worker = new WaitlistProcessor(service, 20)) {
            worker.start();
            awaitPromotion(service, waiting.getId());
            assertFalse(failNextSave.get());
            assertEquals(0, service.getAvailableSpaces(PetType.DOG));
        }
    }

    @Test
    void testClosedProcessorCannotRestart() {
        ReservationService service = new ReservationService(new InMemoryReservationRepository());
        WaitlistProcessor worker = new WaitlistProcessor(service, 20);
        worker.start();
        worker.close();
        assertThrows(IllegalStateException.class, worker::start);
    }
}