package com.petboarding.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;
import com.petboarding.repository.ReservationRepository;

public class ReservationService {

    private final ReservationRepository repository;

    public ReservationService(ReservationRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException(
                    "Repository cannot be null.");
        }

        this.repository = repository;
    }

    // Returns total capacity for each pet category.
    private int getCapacity(PetType petType) {

        if (petType == null) {
            throw new IllegalArgumentException("Invalid pet type.");
        }

        return petType == PetType.DOG ? 3 : 2;
    }

    // Returns the number of available boarding spaces.
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

        // Validate externally supplied pet names.
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

        // Prevent overbooking.
        if (activeReservations >= capacity) {
            throw new IllegalStateException(
                    "No boarding spaces available.");
        }

        // Generate a new reservation ID.
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

        reservations.add(reservation);

        repository.save(reservations);

        return reservation;
    }

    // Cancels an existing reservation.
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
}