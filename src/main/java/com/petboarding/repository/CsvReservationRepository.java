package com.petboarding.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.model.ReservationStatus;

public class CsvReservationRepository
        implements ReservationRepository {

    private static final String HEADER =
            "id,petName,petType,status";

    private static final Pattern ROW = Pattern.compile(
            "^(\\d+),\"((?:[^\"]|\"\")*)\","
            + "(DOG|CAT),(CONFIRMED|CANCELLED)$");

    private final Path file;

    public CsvReservationRepository(Path file) {
        this.file = file.toAbsolutePath();
    }

    // Loads reservation data from a CSV file.
    @Override
    public List<Reservation> load() throws IOException {

        List<Reservation> reservations = new ArrayList<>();

        if (!Files.exists(file)) {
            return reservations;
        }

        List<String> lines =
                Files.readAllLines(file, StandardCharsets.UTF_8);

        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IOException("Invalid CSV file header.");
        }

        Set<Long> ids = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {

            Matcher match = ROW.matcher(lines.get(i));

            if (!match.matches()) {
                throw new IOException(
                        "Invalid CSV record on line " + (i + 1));
            }

            try {
                long id = Long.parseLong(match.group(1));
                String name = match.group(2)
                        .replace("\"\"", "\"");

                PetType type =
                        PetType.valueOf(match.group(3));

                ReservationStatus status =
                        ReservationStatus.valueOf(match.group(4));

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
                        "Invalid CSV reservation data.", e);
            }
        }

        return reservations;
    }

    // Saves reservation data to a CSV file.
    @Override
    public void save(List<Reservation> reservations)
            throws IOException {

        StringBuilder data = new StringBuilder();

        data.append(HEADER).append(System.lineSeparator());

        for (Reservation reservation : reservations) {

            // Escape quotation marks for CSV formatting.
            String name = reservation.getPetName()
                    .replace("\"", "\"\"");

            data.append(reservation.getId())
                    .append(",\"")
                    .append(name)
                    .append("\",")
                    .append(reservation.getPetType())
                    .append(",")
                    .append(reservation.getStatus())
                    .append(System.lineSeparator());
        }

        Files.createDirectories(file.getParent());

        Path temp = Files.createTempFile(
                file.getParent(), "reservations-", ".tmp");

        try {
            Files.writeString(
                    temp, data.toString(), StandardCharsets.UTF_8);

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