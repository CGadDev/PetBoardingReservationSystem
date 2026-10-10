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

    // Initializes the HTTP server and shared reservation service.
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

        // Bind only to the local computer for development.
        server = HttpServer.create(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), port),
                0);

        // Register HTTP endpoints.
        server.createContext(
                "/resources", this::handleResources);

        server.createContext(
                "/reservations", this::handleReservations);

        // Allows multiple HTTP requests to be handled.
        server.setExecutor(Executors.newFixedThreadPool(10));
    }

    // Verifies the API key for protected endpoints.
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

    // Handles GET /resources.
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

    // Handles reservation-related HTTP requests.
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

    // Routes requests to the appropriate operation.
    private ApiResponse processReservationRequest(
            HttpExchange exchange) throws IOException {

        // Require an API key for reservation operations.
        if (!isAuthorized(exchange)) {
            return error(401, "Unauthorized");
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // Operations involving the reservations collection.
        if (path.equals("/reservations")) {

            // GET /reservations
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

            // POST /reservations
            if (method.equals("POST")) {
                return createReservation(exchange);
            }

            exchange.getResponseHeaders()
                    .set("Allow", "GET, POST");

            return error(405, "Method not allowed");
        }

        // Operations involving one reservation.
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

        // GET /reservations/{id}
        if (method.equals("GET")) {

            Reservation found = service.getAllReservations()
                    .stream()
                    .filter(r -> r.getId() == reservationId)
                    .findFirst()
                    .orElseThrow(NoSuchElementException::new);

            return new ApiResponse(
                    200,
                    reservationToMap(found)
            );
        }

        // DELETE /reservations/{id}
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

    // Creates a reservation using a JSON request body.
    private ApiResponse createReservation(
            HttpExchange exchange) throws IOException {

        String contentType = exchange.getRequestHeaders()
                .getFirst("Content-Type");

        // Only accept application/json.
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT)
                        .split(";")[0].trim()
                        .equals("application/json")) {

            return error(415, "JSON content required");
        }

        // Limit the amount of incoming request data.
        byte[] body = exchange.getRequestBody()
                .readNBytes(MAX_BODY_SIZE + 1);

        if (body.length > MAX_BODY_SIZE) {
            return error(413, "Request body too large");
        }

        // Parse supplied JSON safely.
        JsonNode json = mapper.readTree(body);

        if (json == null
                || !json.isObject()
                || json.size() != 2
                || !json.path("petName").isTextual()
                || !json.path("petType").isTextual()) {

            return error(400, "Invalid reservation data");
        }

        String petName = json.get("petName").asText();

        String typeValue = json.get("petType")
                .asText()
                .trim()
                .toUpperCase(Locale.ROOT);

        PetType petType = PetType.valueOf(typeValue);

        // ReservationService performs final validation.
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

    // Converts a reservation into structured JSON.
    private Map<String, Object> reservationToMap(
            Reservation reservation) {

        return Map.of(
                "reservationId", reservation.getId(),
                "petName", reservation.getPetName(),
                "petType", reservation.getPetType().name(),
                "status", reservation.getStatus().name()
        );
    }

    // Creates a standard error response.
    private ApiResponse error(int status, String message) {

        return new ApiResponse(
                status,
                Map.of("error", message)
        );
    }

    // Sends JSON content with the appropriate HTTP status.
    private void sendJson(
            HttpExchange exchange,
            ApiResponse response) throws IOException {

        byte[] responseBytes =
                mapper.writeValueAsBytes(response.body());

        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8");

        exchange.getResponseHeaders().set(
                "Cache-Control", "no-store");

        exchange.sendResponseHeaders(
                response.status(),
                responseBytes.length);

        try (OutputStream output =
                exchange.getResponseBody()) {

            output.write(responseBytes);
        }
    }

    // Internal response representation.
    private record ApiResponse(int status, Object body) {}

    // Starts the HTTP server.
    public void start() {

        server.start();

        System.out.println(
                "HTTP Server running on port "
                + server.getAddress().getPort());
    }

    // Stops the HTTP server.
    public void stop() {
        server.stop(1);
    }
}
