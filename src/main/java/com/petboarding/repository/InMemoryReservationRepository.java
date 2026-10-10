package com.petboarding.repository;

import java.util.ArrayList;
import java.util.List;
import com.petboarding.model.Reservation;

public class InMemoryReservationRepository
        implements ReservationRepository {

    private List<Reservation> reservations = new ArrayList<>();

    @Override
    public synchronized List<Reservation> load() {
        return new ArrayList<>(reservations);
    }

    @Override
    public synchronized void save(List<Reservation> reservations) {
        this.reservations = new ArrayList<>(reservations);
    }
}
