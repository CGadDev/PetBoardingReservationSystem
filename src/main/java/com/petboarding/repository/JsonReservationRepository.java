package com.petboarding.repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;

public class JsonReservationRepository
        implements ReservationRepository {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonReservationRepository(Path file) {
        this.file = file.toAbsolutePath();
    }

    // Loads reservations from JSON.
    @Override
    public List<Reservation> load() throws IOException {

        List<Reservation> reservations = new ArrayList<>();

        if (!Files.exists(file)) {
            return reservations;
        }

        JsonNode root = mapper.readTree(file.toFile());

        if (root == null || !root.isArray()) {
            throw new IOException(
                    "Invalid JSON reservation file.");
        }

        Set<Long> ids = new HashSet<>();

        for (JsonNode item : root) {

            if (!item.isObject()
                    || !item.path("id").isIntegralNumber()
                    || !item.path("id").canConvertToLong()
                    || !item.path("petName").isTextual()
                    || !item.path("petType").isTextual()
                    || !item.path("status").isTextual()) {

                throw new IOException(
                        "Invalid JSON reservation record.");
            }

            try {
                long id = item.get("id").asLong();
                String name = item.get("petName").asText();

                PetType type = PetType.valueOf(
                        item.get("petType").asText());

                ReservationStatus status =
                        ReservationStatus.valueOf(
                                item.get("status").asText());

                if (id <= 0 || !ids.add(id)
                        || name.isBlank()
                        || name.trim().length() > 50
                        || name.contains("|")
                        || name.codePoints().anyMatch(
                                Character::isISOControl)) {

                    throw new IllegalArgumentException();
                }

                reservations.add(new Reservation(
                        id, name, type, status));

            } catch (IllegalArgumentException e) {
                throw new IOException(
                        "Invalid JSON reservation data.", e);
            }
        }

        return reservations;
    }

    // Saves reservations to JSON.
    @Override
    public void save(List<Reservation> reservations)
            throws IOException {

        List<Map<String, Object>> data = new ArrayList<>();

        for (Reservation reservation : reservations) {

            data.add(Map.of(
                    "id", reservation.getId(),
                    "petName", reservation.getPetName(),
                    "petType", reservation.getPetType().name(),
                    "status", reservation.getStatus().name()
            ));
        }

        Files.createDirectories(file.getParent());

        Path temp = Files.createTempFile(
                file.getParent(), "reservations-", ".tmp");

        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), data);

            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);

            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING);
            }

        } finally {
            Files.deleteIfExists(temp);
        }
    }
}