package com.petboarding.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.service.ReservationService;

public class TcpServer {

    private static final int MAX_REQUEST_LENGTH = 256;

    private final ReservationService service;
    private final int port;
    private final byte[] apiKey;

    // Handles multiple TCP clients concurrently.
    private final ExecutorService executor =
            Executors.newFixedThreadPool(10);

    public TcpServer(
            ReservationService service,
            int port,
            String apiKey) {

        if (service == null) {
            throw new IllegalArgumentException(
                    "Reservation service is required.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "An API key is required.");
        }

        this.service = service;
        this.port = port;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    private boolean authenticate(String providedKey) {

        if (providedKey == null) {
            return false;
        }

        return MessageDigest.isEqual(
                apiKey,
                providedKey.getBytes(StandardCharsets.UTF_8));
    }

    // Starts the TCP server.
    public void start() throws IOException {

        try (ServerSocket serverSocket = new ServerSocket(
                port, 50, InetAddress.getLoopbackAddress())) {

            System.out.println(
                    "TCP Server running on port " + port);

            System.out.println("Waiting for clients...");

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSocket = serverSocket.accept();

                executor.submit(
                        () -> handleClient(clientSocket));
            }

        } finally {
            executor.shutdownNow();
        }
    }

    // Handles an individual TCP client.
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

            System.out.println(
                    "Client connected: "
                    + socket.getRemoteSocketAddress());

            output.println(
                    "OK|Connected to Pet Boarding Server");

            boolean authenticated = false;

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

                if (request.isBlank()) {
                    output.println("ERROR|Empty command");
                    continue;
                }

                String[] parts =
                        request.trim().split("\\|", -1);

                String command = parts[0].trim()
                        .toUpperCase(Locale.ROOT);

                // Authenticate the connected client.
                if (command.equals("AUTH")) {

                    if (parts.length != 2) {
                        authenticated = false;
                        output.println(
                                "ERROR|Invalid AUTH command");
                        continue;
                    }

                    authenticated = authenticate(parts[1]);

                    if (authenticated) {
                        output.println("OK|Authenticated");
                    } else {
                        output.println("ERROR|Unauthorized");
                    }

                    continue;
                }

                // Protect reservation operations.
                if (!authenticated
                        && (command.equals("RESERVE")
                        || command.equals("GET")
                        || command.equals("CANCEL"))) {

                    output.println("ERROR|Unauthorized");
                    continue;
                }

                String response = processRequest(request);

                output.println(response);
            }

        } catch (SocketTimeoutException e) {

            System.out.println(
                    "Client connection timed out.");

        } catch (IOException e) {

            System.out.println("Client disconnected.");

        } catch (RuntimeException e) {

            System.err.println(
                    "Unexpected client processing error.");
        }
    }

    // Limits the length of TCP requests.
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

        return request.length() == 0
                ? null
                : request.toString();
    }

    // Processes TCP commands.
    private String processRequest(String request) {

        String[] parts =
                request.trim().split("\\|", -1);

        String command = parts[0].trim()
                .toUpperCase(Locale.ROOT);

        try {

            switch (command) {

                case "LIST":

                    if (parts.length != 1) {
                        return "ERROR|Invalid LIST command";
                    }

                    int dogs =
                            service.getAvailableSpaces(
                                    PetType.DOG);

                    int cats =
                            service.getAvailableSpaces(
                                    PetType.CAT);

                    return "OK|DOG=" + dogs
                            + "|CAT=" + cats;

                case "RESERVE":

                    if (parts.length != 3) {
                        return "ERROR|Invalid RESERVE command";
                    }

                    PetType petType = PetType.valueOf(
                            parts[1].trim()
                                    .toUpperCase(Locale.ROOT));

                    String petName = parts[2].trim();

                    if (petName.isBlank()
                            || petName.length() > 50
                            || petName.codePoints().anyMatch(
                                    Character::isISOControl)) {

                        return "ERROR|Invalid pet name";
                    }

                    Reservation reservation =
                            service.reserve(petName, petType);

                    return "OK|RESERVED|"
                            + reservation.getId()
                            + "|" + reservation.getPetName();

                case "GET":

                    if (parts.length != 2) {
                        return "ERROR|Invalid GET command";
                    }

                    long getId =
                            parseReservationId(parts[1]);

                    Reservation found =
                            service.getAllReservations()
                                    .stream()
                                    .filter(r -> r.getId() == getId)
                                    .findFirst()
                                    .orElseThrow(
                                            NoSuchElementException::new);

                    return "OK|RESERVATION|"
                            + found.getId()
                            + "|" + found.getPetName()
                            + "|" + found.getPetType()
                            + "|" + found.getStatus();

                case "CANCEL":

                    if (parts.length != 2) {
                        return "ERROR|Invalid CANCEL command";
                    }

                    long cancelId =
                            parseReservationId(parts[1]);

                    Reservation cancelled =
                            service.cancel(cancelId);

                    return "OK|CANCELLED|"
                            + cancelled.getId();

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

            System.err.println(
                    "Reservation storage operation failed.");

            return "ERROR|Server error";

        } catch (RuntimeException e) {

            System.err.println(
                    "Unexpected reservation processing error.");

            return "ERROR|Server error";
        }
    }

    private long parseReservationId(String value) {

        long id = Long.parseLong(value.trim());

        if (id <= 0) {
            throw new IllegalArgumentException(
                    "Invalid reservation ID");
        }

        return id;
    }

    private static class RequestTooLongException
            extends IOException {

        private static final long serialVersionUID = 1L;
    }
}