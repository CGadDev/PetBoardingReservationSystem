package com.petboarding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import com.petboarding.api.ReservationHttpServer;
import com.petboarding.network.TcpServer;
import com.petboarding.repository.CsvReservationRepository;
import com.petboarding.repository.JsonReservationRepository;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.repository.ReservationRepository;
import com.petboarding.service.ReservationService;

public class Main {

    public static void main(String[] args) throws IOException {

        String apiKey =
                System.getenv("PET_BOARDING_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println(
                    "Missing PET_BOARDING_API_KEY.");
            return;
        }

        // Select the persistence mechanism.
        String storage = System.getenv()
                .getOrDefault("PET_BOARDING_STORAGE", "csv")
                .trim()
                .toLowerCase(Locale.ROOT);

        ReservationRepository repository;

        switch (storage) {

            case "csv":
                repository = new CsvReservationRepository(
                        Path.of("data", "reservations.csv"));
                break;

            case "json":
                repository = new JsonReservationRepository(
                        Path.of("data", "reservations.json"));
                break;

            case "memory":
                repository =
                        new InMemoryReservationRepository();
                break;

            default:
                System.err.println(
                        "Invalid storage option: " + storage);
                return;
        }

        // Both network interfaces use the same service.
        ReservationService service =
                new ReservationService(repository);

        ReservationHttpServer httpServer =
                new ReservationHttpServer(
                        service, 8082, apiKey);

        TcpServer tcpServer =
                new TcpServer(service, 5050);

        System.out.println(
                "Pet Boarding Reservation System");

        System.out.println(
                "--------------------------------");

        System.out.println("Storage format: " + storage);

        httpServer.start();
        tcpServer.start();
    }
}