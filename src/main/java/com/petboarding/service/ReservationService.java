package com.petboarding.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import com.petboarding.logging.AsyncAuditLogger;
import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.ReservationRepository;

public class ReservationService {

    private final ReservationRepository repository;
    private final AsyncAuditLogger auditLogger;

    // Constructor used by existing unit tests.
    public ReservationService(ReservationRepository repository) {
        this(repository, null);
    }

    // Constructor used by the complete application.
    public ReservationService(
            ReservationRepository repository,
            AsyncAuditLogger auditLogger) {

        if (repository == null) {
            throw new IllegalArgumentException(
                    "Repository cannot be null.");
        }

        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    // Returns boarding capacity.
    private int getCapacity(PetType petType) {

        if (petType == null) {
            throw new IllegalArgumentException(
                    "Invalid pet type.");
        }

        return petType == PetType.DOG ? 3 : 2;
    }

    // Returns remaining boarding spaces.
    public synchronized int getAvailableSpaces(PetType petType)
            throws IOException {

        int capacity = getCapacity(petType);

        long reserved = repository.load().stream()
                .filter(r -> r.getPetType() == petType)
                .filter(r -> r.getStatus()
                        == ReservationStatus.CONFIRMED)
                .count();

        return capacity - (int) reserved;
    }

    // Creates a new reservation.
    public synchronized Reservation reserve(
            String petName, PetType petType)
            throws IOException {

        if (petName == null || petName.isBlank()
                || petName.trim().length() > 50
                || petName.contains("|")
                || petName.codePoints().anyMatch(
                        Character::isISOControl)) {

            throw new IllegalArgumentException(
                    "Invalid pet name.");
        }

        int capacity = getCapacity(petType);

        List<Reservation> reservations =
                new ArrayList<>(repository.load());

        long activeReservations = reservations.stream()
                .filter(r -> r.getPetType() == petType)
                .filter(r -> r.getStatus()
                        == ReservationStatus.CONFIRMED)
                .count();

        if (activeReservations >= capacity) {
            throw new IllegalStateException(
                    "No boarding spaces available.");
        }

        long nextId = reservations.stream()
                .mapToLong(Reservation::getId)
                .max()
                .orElse(1000L) + 1;

        Reservation reservation = new Reservation(
                nextId,
                petName.trim(),
                petType,
                ReservationStatus.CONFIRMED
        );

        // Save the reservation before recording the event.
        reservations.add(reservation);
        repository.save(reservations);

        // Schedule the audit event in the background.
        recordAudit("RESERVED", reservation.getId());

        return reservation;
    }

    // Cancels a reservation.
    public synchronized Reservation cancel(long reservationId)
            throws IOException {

        if (reservationId <= 0) {
            throw new IllegalArgumentException(
                    "Invalid reservation ID.");
        }

        List<Reservation> reservations =
                new ArrayList<>(repository.load());

        for (int i = 0; i < reservations.size(); i++) {

            Reservation reservation = reservations.get(i);

            if (reservation.getId() == reservationId) {

                if (reservation.getStatus()
                        == ReservationStatus.CANCELLED) {

                    throw new IllegalStateException(
                            "Reservation is already cancelled.");
                }

                Reservation cancelled =
                        reservation.withStatus(
                                ReservationStatus.CANCELLED);

                reservations.set(i, cancelled);
                repository.save(reservations);

                // Record the successful cancellation.
                recordAudit("CANCELLED", cancelled.getId());

                return cancelled;
            }
        }

        throw new NoSuchElementException(
                "Reservation not found.");
    }

    // Retrieves all reservations.
    public synchronized List<Reservation> getAllReservations()
            throws IOException {

        return List.copyOf(repository.load());
    }

    // Shared asynchronous audit logging.
    private void recordAudit(String operation, long reservationId) {

        if (auditLogger != null) {
            auditLogger.log(operation, reservationId);
        }
    }
}
