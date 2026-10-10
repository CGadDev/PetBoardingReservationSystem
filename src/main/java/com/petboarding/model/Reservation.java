package com.petboarding.model;

public class Reservation {

    private final long id;
    private final String petName;
    private final PetType petType;
    private final ReservationStatus status;

    public Reservation(long id, String petName,
                       PetType petType, ReservationStatus status) {

        this.id = id;
        this.petName = petName;
        this.petType = petType;
        this.status = status;
    }

    public long getId() {
        return id;
    }

    public String getPetName() {
        return petName;
    }

    public PetType getPetType() {
        return petType;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Reservation withStatus(ReservationStatus newStatus) {
        return new Reservation(id, petName, petType, newStatus);
    }
}
