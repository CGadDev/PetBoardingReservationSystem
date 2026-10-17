package com.petboarding.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.petboarding.model.Reservation;

public class CachingReservationRepository
        implements ReservationRepository {

    private final ReservationRepository repository;
    private List<Reservation> cache;

    public CachingReservationRepository(
            ReservationRepository repository) {

        if (repository == null) {
            throw new IllegalArgumentException(
                    "Repository cannot be null.");
        }

        this.repository = repository;
    }

    // Loads reservations from memory after the first read.
    @Override
    public synchronized List<Reservation> load()
            throws IOException {

        if (cache == null) {
            cache = List.copyOf(repository.load());
        }

        // Return a separate list to protect the cache.
        return new ArrayList<>(cache);
    }

    // Saves changes and refreshes the cached data.
    @Override
    public synchronized void save(
            List<Reservation> reservations)
            throws IOException {

        // Save to persistent storage first.
        repository.save(reservations);

        // Update the cache only after a successful save.
        cache = List.copyOf(reservations);
    }
}