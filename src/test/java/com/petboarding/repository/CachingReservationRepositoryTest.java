package com.petboarding.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.service.ReservationService;
import com.petboarding.service.WaitlistProcessor;

public class CachingReservationRepositoryTest {

    @TempDir
    Path tempDir;

    // Verify repeated reads do not reload persistent storage.
    @Test
    void testRepeatedLoadsUseCache() throws IOException {

        TrackingRepository storage =
                new TrackingRepository();

        ReservationRepository repository =
                new CachingReservationRepository(storage);

        repository.load();
        repository.load();
        repository.load();

        assertEquals(1, storage.loadCount);
    }

    // Verify external list changes do not modify the cache.
    @Test
    void testCachedDataIsProtected() throws IOException {

        TrackingRepository storage =
                new TrackingRepository();

        ReservationRepository repository =
                new CachingReservationRepository(storage);

        List<Reservation> reservations = new ArrayList<>();

        reservations.add(new Reservation(
                1001L,
                "Buddy",
                PetType.DOG,
                ReservationStatus.CONFIRMED));

        repository.save(reservations);

        // Changing the original list must not affect the cache.
        reservations.clear();

        assertEquals(1, repository.load().size());

        // Changing a returned list must not affect the cache.
        List<Reservation> loaded = repository.load();

        loaded.clear();

        assertEquals(1, repository.load().size());
        assertEquals("Buddy",
                repository.load().get(0).getPetName());

        assertEquals(1, storage.saveCount);
        assertEquals(0, storage.loadCount);
    }

    // Verify failed reads can be retried.
    @Test
    void testFailedReadCanRecover() throws IOException {

        TrackingRepository storage =
                new TrackingRepository();

        ReservationRepository repository =
                new CachingReservationRepository(storage);

        storage.failRead = true;

        assertThrows(IOException.class, repository::load);

        storage.failRead = false;

        assertTrue(repository.load().isEmpty());
        assertTrue(repository.load().isEmpty());

        assertEquals(2, storage.loadCount);
    }

    // Verify failed writes invalidate the cached snapshot.
    @Test
    void testFailedWriteDoesNotPublishChanges()
            throws IOException {

        TrackingRepository storage =
                new TrackingRepository();

        ReservationRepository repository =
                new CachingReservationRepository(storage);

        Reservation original = new Reservation(
                1001L,
                "Buddy",
                PetType.DOG,
                ReservationStatus.WAITLISTED);

        repository.save(List.of(original));

        storage.failWrite = true;

        Reservation promoted = original.withStatus(
                ReservationStatus.CONFIRMED);

        assertThrows(IOException.class, () ->
                repository.save(List.of(promoted)));

        storage.failWrite = false;

        // The failed promotion must not appear successful.
        List<Reservation> restored = repository.load();

        assertEquals(ReservationStatus.WAITLISTED,
                restored.get(0).getStatus());

        assertEquals(1, storage.loadCount);

        // Verify that a subsequent write can succeed.
        repository.save(List.of(promoted));

        assertEquals(ReservationStatus.CONFIRMED,
                repository.load().get(0).getStatus());

        assertEquals(ReservationStatus.CONFIRMED,
                storage.records.get(0).getStatus());
    }

    // Verify cached changes remain persisted after recreation.
    @Test
    void testPromotionPersistsAfterRestart()
            throws IOException {

        Path file = tempDir.resolve("reservations.json");

        ReservationRepository repository =
                new CachingReservationRepository(
                        new JsonReservationRepository(file));

        ReservationService service =
                new ReservationService(repository);

        Reservation first =
                service.reserve("Potato", PetType.DOG);

        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);

        Reservation waiting =
                service.reserve("Charlie", PetType.DOG);

        assertEquals(ReservationStatus.WAITLISTED,
                waiting.getStatus());

        service.cancel(first.getId());

        assertEquals(1,
                service.promoteWaitlistedReservations());

        // Recreate the repository and service.
        ReservationService restored =
                new ReservationService(
                        new CachingReservationRepository(
                                new JsonReservationRepository(file)));

        Reservation promoted = restored.getAllReservations()
                .stream()
                .filter(r -> r.getId() == waiting.getId())
                .findFirst()
                .orElseThrow();

        assertEquals(ReservationStatus.CONFIRMED,
                promoted.getStatus());

        assertEquals(0,
                restored.getAvailableSpaces(PetType.DOG));

        // Verify that promotion also reached persistent storage.
        List<Reservation> persisted =
                new JsonReservationRepository(file).load();

        assertTrue(persisted.stream().anyMatch(r ->
                r.getId() == waiting.getId()
                && r.getStatus()
                        == ReservationStatus.CONFIRMED));
    }

    // Verify the asynchronous worker remains correct with caching.
    @Test
    void testBackgroundPromotionWithCache()
            throws Exception {

        Path file = tempDir.resolve("reservations.json");

        ReservationRepository repository =
                new CachingReservationRepository(
                        new JsonReservationRepository(file));

        ReservationService service =
                new ReservationService(repository);

        Reservation first =
                service.reserve("Potato", PetType.DOG);

        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);

        Reservation waiting =
                service.reserve("Charlie", PetType.DOG);

        try (WaitlistProcessor processor =
                new WaitlistProcessor(service, 10)) {

            processor.start();

            service.cancel(first.getId());

            // A new reservation must not bypass Charlie.
            Reservation newer =
                    service.reserve("Daisy", PetType.DOG);

            assertEquals(ReservationStatus.WAITLISTED,
                    newer.getStatus());

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5);

            boolean promoted = false;

            while (System.nanoTime() < deadline) {

                promoted = service.getAllReservations()
                        .stream()
                        .anyMatch(r ->
                                r.getId() == waiting.getId()
                                && r.getStatus()
                                        == ReservationStatus.CONFIRMED);

                if (promoted) {
                    break;
                }

                Thread.sleep(10);
            }

            assertTrue(promoted,
                    "Background promotion did not complete.");

            assertEquals(0,
                    service.getAvailableSpaces(PetType.DOG));

            assertTrue(service.getAllReservations()
                    .stream()
                    .anyMatch(r ->
                            r.getId() == newer.getId()
                            && r.getStatus()
                                    == ReservationStatus.WAITLISTED));
        }
    }

    // Simulates persistent storage and tracks repository operations.
    private static class TrackingRepository
            implements ReservationRepository {

        private List<Reservation> records =
                new ArrayList<>();

        private int loadCount;
        private int saveCount;

        private boolean failRead;
        private boolean failWrite;

        @Override
        public List<Reservation> load() throws IOException {

            loadCount++;

            if (failRead) {
                throw new IOException(
                        "Simulated read failure.");
            }

            return new ArrayList<>(records);
        }

        @Override
        public void save(List<Reservation> reservations)
                throws IOException {

            saveCount++;

            if (failWrite) {
                throw new IOException(
                        "Simulated write failure.");
            }

            records = new ArrayList<>(reservations);
        }
    }
}