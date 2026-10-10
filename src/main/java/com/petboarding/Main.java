package com.petboarding;

import java.io.IOException;

import com.petboarding.api.ReservationHttpServer;
import com.petboarding.network.TcpServer;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.service.ReservationService;

public class Main {

    public static void main(String[] args) throws IOException {

        // Read the configured HTTP API key.
        String apiKey =
                System.getenv("PET_BOARDING_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println(
                    "Missing PET_BOARDING_API_KEY environment variable.");

            return;
        }

        // Create the shared reservation repository.
        InMemoryReservationRepository repository =
                new InMemoryReservationRepository();

        // Create ONE shared reservation service.
        ReservationService service =
                new ReservationService(repository);

        // Configure the HTTP server.
        ReservationHttpServer httpServer =
                new ReservationHttpServer(
                        service,
                        8082,
                        apiKey
                );

        // Configure the TCP server.
        TcpServer tcpServer =
                new TcpServer(
                        service,
                        5050
                );

        System.out.println(
                "Pet Boarding Reservation System");

        System.out.println(
                "--------------------------------");

        // Start the HTTP server.
        httpServer.start();

        // Start the TCP server.
        tcpServer.start();
    }
}