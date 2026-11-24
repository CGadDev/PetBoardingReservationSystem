package com.petboarding.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.InMemoryReservationRepository;

public class ReservationServiceTest {

    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                new InMemoryReservationRepository()
        );
    }

    @Test
    void testInitialAvailability() throws IOException {

        assertEquals(3, service.getAvailableSpaces(PetType.DOG));
        assertEquals(2, service.getAvailableSpaces(PetType.CAT));
    }

    @Test
    void testSuccessfulReservation() throws IOException {

        Reservation reservation = service.reserve("Potato", PetType.DOG);

        assertEquals("Potato", reservation.getPetName());
        assertEquals(PetType.DOG, reservation.getPetType());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(2, service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testCancellation() throws IOException {

        Reservation reservation = service.reserve("Potato", PetType.DOG);

        Reservation cancelled = service.cancel(reservation.getId());

        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(3, service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testOverbookingPrevention() throws IOException {

        service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);

        assertEquals(0, service.getAvailableSpaces(PetType.DOG));

        Reservation waitlisted = service.reserve("Charlie", PetType.DOG);

        assertEquals(ReservationStatus.WAITLISTED, waitlisted.getStatus());
        assertEquals(0, service.getAvailableSpaces(PetType.DOG));
        assertEquals(4, service.getAllReservations().size());
        assertEquals(2, service.getAvailableSpaces(PetType.CAT));
    }

    @Test
    void testWaitlistedReservationCanBeCancelled() throws IOException {

        service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);
        Reservation waiting = service.reserve("Charlie", PetType.DOG);

        Reservation cancelled = service.cancel(waiting.getId());

        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(0, service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testNewReservationCannotBypassWaitlist() throws IOException {

        Reservation first = service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);
        service.reserve("Max", PetType.DOG);
        Reservation waiting = service.reserve("Charlie", PetType.DOG);

        service.cancel(first.getId());
        Reservation newcomer = service.reserve("Daisy", PetType.DOG);

        // Until promotion is implemented, the open space is held
        // for the existing waitlist rather than given to a newcomer.
        assertEquals(ReservationStatus.WAITLISTED, newcomer.getStatus());
        assertTrue(waiting.getId() < newcomer.getId());
        assertEquals(1, service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testIndependentPetCapacity() throws IOException {

        service.reserve("Potato", PetType.DOG);

        assertEquals(2, service.getAvailableSpaces(PetType.DOG));
        assertEquals(2, service.getAvailableSpaces(PetType.CAT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void testInvalidPetNames(String name) {

        assertThrows(IllegalArgumentException.class, () -> {
            service.reserve(name, PetType.DOG);
        });
    }

    @Test
    void testPetNameTooLong() {

        String longName = "A".repeat(51);

        assertThrows(IllegalArgumentException.class, () -> {
            service.reserve(longName, PetType.DOG);
        });
    }

    @Test
    void testDuplicateCancellation() throws IOException {

        Reservation reservation = service.reserve("Potato", PetType.DOG);

        service.cancel(reservation.getId());

        assertThrows(IllegalStateException.class, () -> {
            service.cancel(reservation.getId());
        });
    }
}