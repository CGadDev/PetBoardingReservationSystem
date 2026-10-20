package com.petboarding.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.petboarding.model.PetType;
import com.petboarding.model.Reservation;
import com.petboarding.service.ReservationService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class ReservationHttpServer {

    private static final int MAX_BODY_SIZE = 4096;

    private final HttpServer server;
    private final ReservationService service;
    private final ObjectMapper mapper;
    private final byte[] apiKey;
    private final ExecutorService executor;

    public ReservationHttpServer(
            ReservationService service,
            int port,
            String apiKey) throws IOException {

        if (service == null) {
            throw new IllegalArgumentException(
                    "Reservation service is required.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "An API key must be configured.");
        }

        this.service = service;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);

        mapper = new ObjectMapper();
        mapper.enable(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

        // Port 0 allows tests to use an available port.
        server = HttpServer.create(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), port),
                0);

        server.createContext(
                "/resources", this::handleResources);

        server.createContext(
                "/reservations", this::handleReservations);

        executor = Executors.newFixedThreadPool(20);
        server.setExecutor(executor);
    }

    // Verifies the API key.
    private boolean isAuthorized(HttpExchange exchange) {

        String provided = exchange.getRequestHeaders()
                .getFirst("X-API-Key");

        if (provided == null) {
            return false;
        }

        return MessageDigest.isEqual(
                apiKey,
                provided.getBytes(StandardCharsets.UTF_8));
    }

    // Handles requests for boarding availability.
    private void handleResources(HttpExchange exchange)
            throws IOException {

        ApiResponse response;

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (!path.equals("/resources")) {

            response = error(404, "Not found");

        } else if (!method.equals("GET")) {

            exchange.getResponseHeaders().set("Allow", "GET");
            response = error(405, "Method not allowed");

        } else {

            try {

                int dogs = service.getAvailableSpaces(PetType.DOG);
                int cats = service.getAvailableSpaces(PetType.CAT);

                response = new ApiResponse(
                        200,
                        Map.of("DOG", dogs, "CAT", cats)
                );

            } catch (IOException e) {

                System.err.println(
                        "Unable to retrieve availability.");

                response = error(500, "Server error");
            }
        }

        sendJson(exchange, response);
    }

    // Handles reservation-related requests.
    private void handleReservations(HttpExchange exchange)
            throws IOException {

        ApiResponse response;

        try {

            response = processReservationRequest(exchange);

        } catch (JsonProcessingException e) {

            response = error(400, "Malformed JSON");

        } catch (NoSuchElementException e) {

            response = error(404, "Reservation not found");

        } catch (IllegalArgumentException e) {

            response = error(400, "Invalid input");

        } catch (IllegalStateException e) {

            response = error(409, "Reservation conflict");

        } catch (IOException e) {

            System.err.println(
                    "Reservation processing failed.");

            response = error(500, "Server error");
        }

        sendJson(exchange, response);
    }

    // Determines the requested reservation operation.
    private ApiResponse processReservationRequest(
            HttpExchange exchange) throws IOException {

        if (!isAuthorized(exchange)) {
            return error(401, "Unauthorized");
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // Operations on the reservation collection.
        if (path.equals("/reservations")) {

            if (method.equals("GET")) {

                List<Map<String, Object>> reservations =
                        service.getAllReservations()
                                .stream()
                                .map(this::reservationToMap)
                                .toList();

                return new ApiResponse(
                        200,
                        Map.of("reservations", reservations)
                );
            }

            if (method.equals("POST")) {
                return createReservation(exchange);
            }

            exchange.getResponseHeaders()
                    .set("Allow", "GET, POST");

            return error(405, "Method not allowed");
        }

        String prefix = "/reservations/";

        if (!path.startsWith(prefix)) {
            return error(404, "Not found");
        }

        String idText = path.substring(prefix.length());

        if (idText.isBlank() || idText.contains("/")) {
            return error(404, "Not found");
        }

        long reservationId = Long.parseLong(idText);

        if (reservationId <= 0) {
            return error(400, "Invalid reservation ID");
        }

        // Retrieve an individual reservation.
        if (method.equals("GET")) {

            Reservation found =
                    service.getAllReservations()
                            .stream()
                            .filter(r -> r.getId() == reservationId)
                            .findFirst()
                            .orElseThrow(NoSuchElementException::new);

            return new ApiResponse(
                    200,
                    reservationToMap(found)
            );
        }

        // Cancel an individual reservation.
        if (method.equals("DELETE")) {

            Reservation cancelled =
                    service.cancel(reservationId);

            return new ApiResponse(
                    200,
                    reservationToMap(cancelled)
            );
        }

        exchange.getResponseHeaders()
                .set("Allow", "GET, DELETE");

        return error(405, "Method not allowed");
    }

    // Creates a reservation from a JSON body.
    private ApiResponse createReservation(
            HttpExchange exchange) throws IOException {

        String contentType = exchange.getRequestHeaders()
                .getFirst("Content-Type");

        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT)
                        .split(";")[0].trim()
                        .equals("application/json")) {

            return error(415, "JSON content required");
        }

        byte[] body = exchange.getRequestBody()
                .readNBytes(MAX_BODY_SIZE + 1);

        if (body.length > MAX_BODY_SIZE) {
            return error(413, "Request body too large");
        }

        JsonNode json = mapper.readTree(body);

        if (json == null
                || !json.isObject()
                || json.size() != 2
                || !json.path("petName").isTextual()
                || !json.path("petType").isTextual()) {

            return error(400, "Invalid reservation data");
        }

        String petName =
                json.get("petName").asText();

        PetType petType = PetType.valueOf(
                json.get("petType").asText()
                        .trim()
                        .toUpperCase(Locale.ROOT));

        Reservation reservation =
                service.reserve(petName, petType);

        exchange.getResponseHeaders().set(
                "Location",
                "/reservations/" + reservation.getId());

        return new ApiResponse(
                201,
                reservationToMap(reservation)
        );
    }

    // Converts reservation information to JSON fields.
    private Map<String, Object> reservationToMap(
            Reservation reservation) {

        return Map.of(
                "reservationId", reservation.getId(),
                "petName", reservation.getPetName(),
                "petType", reservation.getPetType().name(),
                "status", reservation.getStatus().name()
        );
    }

    private ApiResponse error(int status, String message) {

        return new ApiResponse(
                status,
                Map.of("error", message)
        );
    }

    // Sends structured HTTP responses.
    private void sendJson(
            HttpExchange exchange,
            ApiResponse response) throws IOException {

        byte[] bytes =
                mapper.writeValueAsBytes(response.body());

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8");

        exchange.getResponseHeaders().set(
                "Cache-Control", "no-store");

        exchange.sendResponseHeaders(
                response.status(), bytes.length);

        try (OutputStream output =
                exchange.getResponseBody()) {

            output.write(bytes);
        }
    }

    private record ApiResponse(int status, Object body) {}

    // Returns the assigned port for integration tests.
    public int getPort() {
        return server.getAddress().getPort();
    }

    // Starts the HTTP server.
    public void start() {

        server.start();

        System.out.println(
                "HTTP Server running on port " + getPort());
    }

    // Stops the HTTP server and its worker threads.
    public void stop() {

        server.stop(1);
        executor.shutdownNow();
    }
}
