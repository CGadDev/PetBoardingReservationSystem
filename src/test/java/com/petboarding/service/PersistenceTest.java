package com.petboarding.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.CsvReservationRepository;
import com.petboarding.repository.JsonReservationRepository;
import com.petboarding.repository.ReservationRepository;

public class PersistenceTest {

    @TempDir
    Path tempDir;

    // Creates a repository using the selected format.
    private ReservationRepository createRepository(
            String format, Path file) {

        if (format.equals("csv")) {
            return new CsvReservationRepository(file);
        }

        return new JsonReservationRepository(file);
    }

    private Path getFile(String format) {
        return tempDir.resolve("reservations." + format);
    }

    // Verify reservations survive service recreation.
    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void testSaveAndReload(String format) throws Exception {

        Path file = getFile(format);

        ReservationService service = new ReservationService(
                createRepository(format, file));

        Reservation reservation =
                service.reserve("Potato", PetType.DOG);

        assertTrue(Files.exists(file));

        // Create a new service using the same saved file.
        ReservationService restored = new ReservationService(
                createRepository(format, file));

        assertEquals(1,
                restored.getAllReservations().size());

        Reservation saved =
                restored.getAllReservations().get(0);

        assertEquals(reservation.getId(), saved.getId());
        assertEquals("Potato", saved.getPetName());
        assertEquals(PetType.DOG, saved.getPetType());
        assertEquals(ReservationStatus.CONFIRMED,
                saved.getStatus());

        assertEquals(2,
                restored.getAvailableSpaces(PetType.DOG));
    }

    // Verify cancelled reservations remain cancelled.
    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void testCancellationPersists(String format)
            throws Exception {

        Path file = getFile(format);

        ReservationService service = new ReservationService(
                createRepository(format, file));

        Reservation reservation =
                service.reserve("Whiskers", PetType.CAT);

        service.cancel(reservation.getId());

        ReservationService restored = new ReservationService(
                createRepository(format, file));

        assertEquals(ReservationStatus.CANCELLED,
                restored.getAllReservations().get(0).getStatus());

        assertEquals(2,
                restored.getAvailableSpaces(PetType.CAT));
    }

    // Verify saved reservations still enforce capacity.
    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void testCapacityAfterRestart(String format)
            throws Exception {

        Path file = getFile(format);

        ReservationService service = new ReservationService(
                createRepository(format, file));

        service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);

        ReservationService restored = new ReservationService(
                createRepository(format, file));

        assertEquals(0,
                restored.getAvailableSpaces(PetType.DOG));

        assertThrows(IllegalStateException.class, () -> {
            restored.reserve("Charlie", PetType.DOG);
        });

        assertEquals(3,
                restored.getAllReservations().size());
    }

    // Verify corrupted storage records are rejected.
    @ParameterizedTest
    @ValueSource(strings = {"csv", "json"})
    void testInvalidStorageData(String format)
            throws Exception {

        Path file = getFile(format);

        String invalidData;

        if (format.equals("csv")) {
            invalidData =
                    "id,petName,petType,status\ninvalid-record";
        } else {
            invalidData = "{invalid json";
        }

        Files.writeString(file, invalidData);

        ReservationRepository repository =
                createRepository(format, file);

        assertThrows(IOException.class, repository::load);
    }
}