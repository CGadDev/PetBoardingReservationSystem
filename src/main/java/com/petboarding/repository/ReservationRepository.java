package com.petboarding.repository;

import java.io.IOException;
import java.util.List;
import com.petboarding.model.Reservation;

public interface ReservationRepository {

    List<Reservation> load() throws IOException;

    void save(List<Reservation> reservations) throws IOException;
}