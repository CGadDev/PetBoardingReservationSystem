package com.petboarding.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.petboarding.model.PetType;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.service.ReservationService;

public class HttpApiIntegrationTest {

    private static final String TEST_KEY = "test-only-api-key";

    private ReservationHttpServer server;
    private ReservationService service;
    private HttpClient client;
    private ObjectMapper mapper;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {

        service = new ReservationService(
                new InMemoryReservationRepository());

        // Port 0 selects an available local port.
        server = new ReservationHttpServer(
                service, 0, TEST_KEY);

        server.start();

        baseUrl = "http://localhost:" + server.getPort();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {

        if (server != null) {
            server.stop();
        }
    }

    // Sends requests to the actual HTTP server.
    private HttpResponse<String> send(
            String method,
            String path,
            String body,
            String key) throws Exception {

        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(5));

        if (key != null) {
            builder.header("X-API-Key", key);
        }

        switch (method) {

            case "GET":
                builder.GET();
                break;

            case "POST":
                builder.header(
                        "Content-Type", "application/json");

                builder.POST(
                        HttpRequest.BodyPublishers.ofString(
                                body == null ? "" : body));
                break;

            case "DELETE":
                builder.DELETE();
                break;

            default:
                builder.method(
                        method,
                        HttpRequest.BodyPublishers.noBody());
                break;
        }

        return client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testGetAvailability() throws Exception {

        HttpResponse<String> response =
                send("GET", "/resources", null, null);

        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());

        assertEquals(3, json.get("DOG").asInt());
        assertEquals(2, json.get("CAT").asInt());
    }

    @Test
    void testReservationLifecycle() throws Exception {

        // Create a reservation.
        HttpResponse<String> created = send(
                "POST",
                "/reservations",
                "{\"petName\":\"Potato\",\"petType\":\"DOG\"}",
                TEST_KEY);

        assertEquals(201, created.statusCode());

        JsonNode reservation =
                mapper.readTree(created.body());

        long id = reservation.get("reservationId").asLong();

        assertEquals("Potato",
                reservation.get("petName").asText());

        assertEquals("CONFIRMED",
                reservation.get("status").asText());

        // Retrieve the saved reservation.
        HttpResponse<String> retrieved = send(
                "GET",
                "/reservations/" + id,
                null,
                TEST_KEY);

        assertEquals(200, retrieved.statusCode());

        // Check updated availability.
        HttpResponse<String> availability = send(
                "GET", "/resources", null, null);

        JsonNode spaces =
                mapper.readTree(availability.body());

        assertEquals(2, spaces.get("DOG").asInt());

        // Cancel the reservation.
        HttpResponse<String> cancelled = send(
                "DELETE",
                "/reservations/" + id,
                null,
                TEST_KEY);

        assertEquals(200, cancelled.statusCode());

        assertEquals("CANCELLED",
                mapper.readTree(cancelled.body())
                        .get("status").asText());

        // Verify availability was restored.
        HttpResponse<String> restored = send(
                "GET", "/resources", null, null);

        assertEquals(3,
                mapper.readTree(restored.body())
                        .get("DOG").asInt());
    }

    @Test
    void testUnauthorizedRequests() throws Exception {

        HttpResponse<String> missingKey = send(
                "GET", "/reservations", null, null);

        assertEquals(401, missingKey.statusCode());

        HttpResponse<String> wrongKey = send(
                "GET", "/reservations", null, "wrong-key");

        assertEquals(401, wrongKey.statusCode());

        HttpResponse<String> unauthorizedPost = send(
                "POST",
                "/reservations",
                "{\"petName\":\"Potato\",\"petType\":\"DOG\"}",
                null);

        assertEquals(401, unauthorizedPost.statusCode());
    }

    @Test
    void testInvalidReservationInput() throws Exception {

        // Malformed JSON.
        HttpResponse<String> malformed = send(
                "POST",
                "/reservations",
                "{invalid json",
                TEST_KEY);

        assertEquals(400, malformed.statusCode());

        // Invalid pet name.
        HttpResponse<String> invalidName = send(
                "POST",
                "/reservations",
                "{\"petName\":\"   \",\"petType\":\"DOG\"}",
                TEST_KEY);

        assertEquals(400, invalidName.statusCode());

        // Unsupported pet category.
        HttpResponse<String> invalidType = send(
                "POST",
                "/reservations",
                "{\"petName\":\"Potato\",\"petType\":\"HORSE\"}",
                TEST_KEY);

        assertEquals(400, invalidType.statusCode());

        assertTrue(service.getAllReservations().isEmpty());
    }

    @Test
    void testOverbookingReturnsConflict() throws Exception {

        for (int i = 0; i < 3; i++) {

            String body = "{\"petName\":\"Dog" + i
                    + "\",\"petType\":\"DOG\"}";

            HttpResponse<String> response = send(
                    "POST", "/reservations", body, TEST_KEY);

            assertEquals(201, response.statusCode());
        }

        // Attempt a fourth dog reservation.
        HttpResponse<String> conflict = send(
                "POST",
                "/reservations",
                "{\"petName\":\"Charlie\",\"petType\":\"DOG\"}",
                TEST_KEY);

        assertEquals(409, conflict.statusCode());

        assertEquals(0,
                service.getAvailableSpaces(PetType.DOG));
    }

    @Test
    void testNotFoundAndUnsupportedMethod()
            throws Exception {

        HttpResponse<String> missing = send(
                "GET",
                "/reservations/9999",
                null,
                TEST_KEY);

        assertEquals(404, missing.statusCode());

        HttpResponse<String> invalidId = send(
                "GET",
                "/reservations/abc",
                null,
                TEST_KEY);

        assertEquals(400, invalidId.statusCode());

        HttpResponse<String> wrongMethod = send(
                "POST",
                "/resources",
                "{}",
                TEST_KEY);

        assertEquals(405, wrongMethod.statusCode());
    }

    @Test
    void testHttpUsesSharedReservationService()
            throws Exception {

        // Modify the service directly.
        service.reserve("Buddy", PetType.DOG);

        // The HTTP endpoint must reflect that change.
        HttpResponse<String> response = send(
                "GET",
                "/resources",
                null,
                null);

        assertEquals(200, response.statusCode());

        JsonNode json =
                mapper.readTree(response.body());

        assertEquals(2, json.get("DOG").asInt());
        assertEquals(2, json.get("CAT").asInt());
    }
}