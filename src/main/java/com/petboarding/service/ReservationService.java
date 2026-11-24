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

        // Existing waitlisted pets have priority over new requests.
        boolean hasWaitlist = reservations.stream()
                .anyMatch(r -> r.getPetType() == petType
                        && r.getStatus() == ReservationStatus.WAITLISTED);

        ReservationStatus status = activeReservations >= capacity
                || hasWaitlist
                ? ReservationStatus.WAITLISTED
                : ReservationStatus.CONFIRMED;

        long nextId = reservations.stream()
                .mapToLong(Reservation::getId)
                .max()
                .orElse(1000L) + 1;

        Reservation reservation = new Reservation(
                nextId,
                petName.trim(),
                petType,
                status
        );

        // Save the reservation before recording the event.
        reservations.add(reservation);
        repository.save(reservations);

        // Schedule the audit event in the background.
        recordAudit(status == ReservationStatus.WAITLISTED
                ? "WAITLISTED" : "RESERVED", reservation.getId());

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

    // Promotes the oldest eligible reservations for each pet type.
    // The same service lock protects requests and background processing.
    public synchronized int promoteWaitlistedReservations()
            throws IOException {

        List<Reservation> reservations =
                new ArrayList<>(repository.load());
        List<Reservation> promoted = new ArrayList<>();

        for (PetType petType : PetType.values()) {

            long confirmed = reservations.stream()
                    .filter(r -> r.getPetType() == petType
                            && r.getStatus() == ReservationStatus.CONFIRMED)
                    .count();

            // Reservation IDs increase in creation order.
            while (confirmed < getCapacity(petType)) {
                int oldest = -1;

                for (int i = 0; i < reservations.size(); i++) {
                    Reservation candidate = reservations.get(i);

                    if (candidate.getPetType() == petType
                            && candidate.getStatus() == ReservationStatus.WAITLISTED
                            && (oldest == -1 || candidate.getId()
                                    < reservations.get(oldest).getId())) {
                        oldest = i;
                    }
                }

                if (oldest == -1) {
                    break;
                }

                Reservation reservation = reservations.get(oldest)
                        .withStatus(ReservationStatus.CONFIRMED);
                reservations.set(oldest, reservation);
                promoted.add(reservation);
                confirmed++;
            }
        }

        // Save all changes together; never write an unchanged snapshot.
        if (!promoted.isEmpty()) {
            repository.save(reservations);

            // Audit only after the repository accepts the changes.
            for (Reservation reservation : promoted) {
                recordAudit("PROMOTED", reservation.getId());
            }
        }

        return promoted.size();
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