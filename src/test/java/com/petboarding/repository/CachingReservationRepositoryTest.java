package com.petboarding.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.service.ReservationService;

public class CachingReservationRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void cachedAvailabilityUpdatesAfterReservationAndCancellation()
            throws IOException {

        Path file = tempDir.resolve("reservations.csv");

        ReservationRepository repository =
                new CachingReservationRepository(
                        new CsvReservationRepository(file));

        ReservationService service =
                new ReservationService(repository);

        // Initially, all dog boarding spaces are available.
        assertEquals(3,
                service.getAvailableSpaces(PetType.DOG));

        // Reserving a dog should reduce availability.
        Reservation reservation =
                service.reserve("Buddy", PetType.DOG);

        assertEquals(2,
                service.getAvailableSpaces(PetType.DOG));

        // Cancelling should restore availability.
        service.cancel(reservation.getId());

        assertEquals(3,
                service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void cachedReservationsRemainPersisted()
            throws IOException {

        Path file = tempDir.resolve("reservations.csv");

        ReservationRepository repository =
                new CachingReservationRepository(
                        new CsvReservationRepository(file));

        ReservationService service =
                new ReservationService(repository);

        service.reserve("Max", PetType.CAT);

        // Load directly from the CSV file to verify persistence.
        CsvReservationRepository csvRepository =
                new CsvReservationRepository(file);

        List<Reservation> saved = csvRepository.load();

        assertEquals(1, saved.size());
        assertEquals("Max", saved.get(0).getPetName());
        assertEquals(ReservationStatus.CONFIRMED,
                saved.get(0).getStatus());
    }

    @Test
    void cachedLoadReturnsIndependentList()
            throws IOException {

        Path file = tempDir.resolve("reservations.csv");

        ReservationRepository repository =
                new CachingReservationRepository(
                        new CsvReservationRepository(file));

        ReservationService service =
                new ReservationService(repository);

        service.reserve("Luna", PetType.DOG);

        List<Reservation> loaded = repository.load();

        // Changing this returned list must not change the cache.
        loaded.clear();

        assertEquals(1, repository.load().size());
        assertEquals(2,
                service.getAvailableSpaces(PetType.DOG));
    }
}
