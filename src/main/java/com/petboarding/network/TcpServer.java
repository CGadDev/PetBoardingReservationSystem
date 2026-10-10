package com.petboarding.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.*;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.service.ReservationService;

public class TcpServer {

    private static final int MAX_REQUEST_LENGTH = 256;

    private final ReservationService service;
    private final byte[] apiKey;
    private final ServerSocket serverSocket;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(10);

    private final Set<Socket> clients =
            ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;

    public TcpServer(
            ReservationService service,
            int port,
            String apiKey) throws IOException {

        if (service == null) {
            throw new IllegalArgumentException(
                    "Reservation service is required.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "An API key is required.");
        }

        this.service = service;
        this.apiKey =
                apiKey.getBytes(StandardCharsets.UTF_8);

        // Port 0 selects an available port during testing.
        this.serverSocket = new ServerSocket(
                port, 50, InetAddress.getLoopbackAddress());
    }

    // Returns the actual server port.
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private boolean authenticate(String providedKey) {

        return providedKey != null
                && MessageDigest.isEqual(
                        apiKey,
                        providedKey.getBytes(StandardCharsets.UTF_8));
    }

    // Accepts multiple TCP connections.
    public void start() {

        System.out.println(
                "TCP Server running on port " + getPort());

        System.out.println("Waiting for clients...");

        try {
            while (running) {

                try {
                    Socket socket = serverSocket.accept();

                    if (!running) {
                        socket.close();
                        break;
                    }

                    clients.add(socket);

                    try {
                        executor.execute(
                                () -> handleClient(socket));

                    } catch (RejectedExecutionException e) {
                        clients.remove(socket);
                        socket.close();
                    }

                } catch (SocketException e) {

                    if (running) {
                        System.err.println(
                                "TCP socket error.");
                    }

                    break;
                }
            }

        } catch (IOException e) {

            if (running) {
                System.err.println(
                        "TCP server communication error.");
            }

        } finally {
            stop();
        }
    }

    // Handles requests from one client.
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

            boolean authenticated = false;

            output.println(
                    "OK|Connected to Pet Boarding Server");

            while (running) {

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

                // Authenticate this connection.
                if (command.equals("AUTH")) {

                    if (parts.length != 2) {
                        authenticated = false;
                        output.println(
                                "ERROR|Invalid AUTH command");
                        continue;
                    }

                    authenticated = authenticate(parts[1]);

                    output.println(
                            authenticated
                                    ? "OK|Authenticated"
                                    : "ERROR|Unauthorized");

                    continue;
                }

                // Restrict reservation operations.
                if (!authenticated
                        && (command.equals("RESERVE")
                        || command.equals("GET")
                        || command.equals("CANCEL"))) {

                    output.println("ERROR|Unauthorized");
                    continue;
                }

                output.println(processRequest(request));
            }

        } catch (SocketTimeoutException e) {

            System.out.println(
                    "Client connection timed out.");

        } catch (IOException e) {

            if (running) {
                System.out.println(
                        "Client disconnected.");
            }

        } catch (RuntimeException e) {

            System.err.println(
                    "Unexpected client processing error.");

        } finally {
            clients.remove(socket);
        }
    }

    // Reads a bounded command from the client.
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

    // Processes the supported TCP commands.
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

                    int dogs = service.getAvailableSpaces(
                            PetType.DOG);

                    int cats = service.getAvailableSpaces(
                            PetType.CAT);

                    return "OK|DOG=" + dogs + "|CAT=" + cats;

                case "RESERVE":

                    if (parts.length != 3) {
                        return "ERROR|Invalid RESERVE command";
                    }

                    PetType petType = PetType.valueOf(
                            parts[1].trim()
                                    .toUpperCase(Locale.ROOT));

                    String petName = parts[2].trim();

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

    // Stops the server and closes connected sockets.
    public void stop() {

        running = false;

        try {
            serverSocket.close();
        } catch (IOException e) {
            // Socket may already be closed.
        }

        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException e) {
                // Continue closing other clients.
            }
        }

        executor.shutdownNow();
    }

    private static class RequestTooLongException
            extends IOException {

        private static final long serialVersionUID = 1L;
    }
}
