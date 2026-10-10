package com.petboarding.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.service.ReservationService;
import com.petboarding.logging.AsyncAuditLogger;

public class TcpServer {

    private static final int MAX_REQUEST_LENGTH = 256;

    private final ReservationService service;
    private final int port;

    // Thread pool for handling multiple clients.
    private final ExecutorService executor =
            Executors.newFixedThreadPool(10);

    // Asynchronous logger for reservation activity.
    private final AsyncAuditLogger auditLogger =
            new AsyncAuditLogger();

    public TcpServer(ReservationService service, int port) {
        this.service = service;
        this.port = port;
    }

    // Starts the TCP server.
    public void start() throws IOException {

        try (ServerSocket serverSocket = new ServerSocket(
                port, 50, InetAddress.getLoopbackAddress())) {

            System.out.println("TCP Server running on port " + port);
            System.out.println("Waiting for clients...");

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSocket = serverSocket.accept();

                executor.submit(() -> handleClient(clientSocket));
            }

        } finally {
            executor.shutdownNow();
            auditLogger.close();
        }
    }

    // Handles communication with an individual client.
    private void handleClient(Socket socket) {

        try (socket;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(
                             socket.getInputStream(),
                             StandardCharsets.UTF_8));

             PrintWriter output = new PrintWriter(
                     new OutputStreamWriter(
                             socket.getOutputStream(),
                             StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(300000);

            System.out.println("Client connected: "
                    + socket.getRemoteSocketAddress());

            output.println("OK|Connected to Pet Boarding Server");

            while (true) {

                String request;

                try {
                    request = readLimitedLine(input);

                } catch (RequestTooLongException e) {
                    output.println("ERROR|Request too long");
                    break;
                }

                if (request == null) {
                    break;
                }

                if (request.trim().equalsIgnoreCase("QUIT")) {
                    output.println("OK|Goodbye");
                    break;
                }

                String response = processRequest(request);

                output.println(response);
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Client connection timed out.");

        } catch (IOException e) {
            System.out.println("Client disconnected.");

        } catch (RuntimeException e) {
            System.err.println("Unexpected client processing error.");
        }
    }

    // Limits incoming request length.
    private String readLimitedLine(BufferedReader input)
            throws IOException {

        StringBuilder request = new StringBuilder();
        int character;

        while ((character = input.read()) != -1) {

            if (character == '\n') {
                return request.toString();
            }

            if (character == '\r') {
                continue;
            }

            if (request.length() >= MAX_REQUEST_LENGTH) {
                throw new RequestTooLongException();
            }

            request.append((char) character);
        }

        return request.length() == 0 ? null : request.toString();
    }

    // Processes commands received from TCP clients.
    private String processRequest(String request) {

        if (request == null || request.isBlank()) {
            return "ERROR|Empty command";
        }

        String[] parts = request.trim().split("\\|", -1);

        String command = parts[0].trim()
                .toUpperCase(Locale.ROOT);

        try {

            switch (command) {

                // Displays available boarding spaces.
                case "LIST":

                    if (parts.length != 1) {
                        return "ERROR|Invalid LIST command";
                    }

                    int dogs = service.getAvailableSpaces(PetType.DOG);
                    int cats = service.getAvailableSpaces(PetType.CAT);

                    return "OK|DOG=" + dogs + "|CAT=" + cats;

                // Creates a new reservation.
                case "RESERVE":

                    if (parts.length != 3) {
                        return "ERROR|Invalid RESERVE command";
                    }

                    PetType petType = PetType.valueOf(
                            parts[1].trim().toUpperCase(Locale.ROOT));

                    String petName = parts[2].trim();

                    if (petName.isBlank()
                            || petName.length() > 50
                            || petName.chars().anyMatch(
                                    Character::isISOControl)) {

                        return "ERROR|Invalid pet name";
                    }

                    Reservation reservation = service.reserve(
                            petName, petType);

                    // Record successful reservation asynchronously.
                    auditLogger.log(
                            "RESERVED",
                            reservation.getId()
                    );

                    return "OK|RESERVED|"
                            + reservation.getId()
                            + "|" + reservation.getPetName();

                // Retrieves an existing reservation.
                case "GET":

                    if (parts.length != 2) {
                        return "ERROR|Invalid GET command";
                    }

                    long getId = parseReservationId(parts[1]);

                    Reservation found = service.getAllReservations()
                            .stream()
                            .filter(r -> r.getId() == getId)
                            .findFirst()
                            .orElseThrow(NoSuchElementException::new);

                    return "OK|RESERVATION|"
                            + found.getId()
                            + "|" + found.getPetName()
                            + "|" + found.getPetType()
                            + "|" + found.getStatus();

                // Cancels an existing reservation.
                case "CANCEL":

                    if (parts.length != 2) {
                        return "ERROR|Invalid CANCEL command";
                    }

                    long cancelId = parseReservationId(parts[1]);

                    Reservation cancelled = service.cancel(cancelId);

                    // Record successful cancellation asynchronously.
                    auditLogger.log(
                            "CANCELLED",
                            cancelled.getId()
                    );

                    return "OK|CANCELLED|" + cancelled.getId();

                default:
                    return "ERROR|Unknown command";
            }

        } catch (IllegalArgumentException e) {
            return "ERROR|Invalid input";

        } catch (NoSuchElementException e) {
            return "ERROR|Reservation not found";

        } catch (IllegalStateException e) {

            if (command.equals("RESERVE")) {
                return "ERROR|No spaces available";
            }

            if (command.equals("CANCEL")) {
                return "ERROR|Reservation already cancelled";
            }

            return "ERROR|Operation unsuccessful";

        } catch (IOException e) {
            System.err.println("Reservation storage operation failed.");
            return "ERROR|Server error";

        } catch (RuntimeException e) {
            System.err.println("Unexpected reservation processing error.");
            return "ERROR|Server error";
        }
    }

    // Validates reservation IDs.
    private long parseReservationId(String value) {

        long id = Long.parseLong(value.trim());

        if (id <= 0) {
            throw new IllegalArgumentException(
                    "Invalid reservation ID");
        }

        return id;
    }

    // Exception for oversized TCP requests.
    private static class RequestTooLongException
            extends IOException {

        private static final long serialVersionUID = 1L;
    }
}
