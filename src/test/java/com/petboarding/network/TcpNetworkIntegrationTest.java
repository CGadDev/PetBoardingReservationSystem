package com.petboarding.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.petboarding.api.ReservationHttpServer;
import com.petboarding.model.PetType;
import com.petboarding.repository.InMemoryReservationRepository;
import com.petboarding.service.ReservationService;

public class TcpNetworkIntegrationTest {

    private static final String TEST_KEY = "tcp-test-key";

    private ReservationService service;
    private TcpServer server;
    private Thread serverThread;
    private int port;

    @BeforeEach
    void setUp() throws Exception {

        service = new ReservationService(
                new InMemoryReservationRepository());

        // Use a dynamically assigned local port.
        server = new TcpServer(service, 0, TEST_KEY);

        port = server.getPort();

        // Run the blocking TCP server in another thread.
        serverThread = new Thread(
                server::start, "tcp-test-server");

        serverThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {

        if (server != null) {
            server.stop();
        }

        if (serverThread != null) {
            serverThread.join(2000);

            assertFalse(serverThread.isAlive(),
                    "TCP server did not stop.");
        }
    }

    // Represents one real TCP socket connection.
    private static class TestClient implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader input;
        private final PrintWriter output;

        TestClient(int port) throws IOException {

            socket = new Socket(
                    InetAddress.getLoopbackAddress(), port);

            socket.setSoTimeout(5000);

            input = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));

            output = new PrintWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8), true);

            String welcome = input.readLine();

            if (!"OK|Connected to Pet Boarding Server"
                    .equals(welcome)) {

                throw new IOException(
                        "Unexpected server welcome message.");
            }
        }

        String send(String command) throws IOException {

            output.println(command);

            String response = input.readLine();

            if (response == null) {
                throw new EOFException(
                        "Server closed the connection.");
            }

            return response;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void testMultipleClientsShareReservations()
            throws Exception {

        try (TestClient clientA = new TestClient(port);
             TestClient clientB = new TestClient(port)) {

            assertEquals("OK|DOG=3|CAT=2",
                    clientA.send("LIST"));

            assertEquals("OK|Authenticated",
                    clientA.send("AUTH|" + TEST_KEY));

            assertEquals("OK|RESERVED|1001|Potato",
                    clientA.send("RESERVE|DOG|Potato"));

            // Client B sees Client A's modification.
            assertEquals("OK|DOG=2|CAT=2",
                    clientB.send("LIST"));

            assertEquals("OK|Authenticated",
                    clientB.send("AUTH|" + TEST_KEY));

            assertEquals("OK|CANCELLED|1001",
                    clientB.send("CANCEL|1001"));

            // Client A sees Client B's cancellation.
            assertEquals("OK|DOG=3|CAT=2",
                    clientA.send("LIST"));

            assertEquals(
                    "OK|RESERVATION|1001|Potato|DOG|CANCELLED",
                    clientA.send("GET|1001"));
        }
    }

    @Test
    void testAuthenticationIsPerClient()
            throws Exception {

        try (TestClient clientA = new TestClient(port);
             TestClient clientB = new TestClient(port)) {

            assertEquals("ERROR|Unauthorized",
                    clientA.send("GET|1001"));

            assertEquals("ERROR|Unauthorized",
                    clientA.send("AUTH|wrong-key"));

            assertEquals("ERROR|Unauthorized",
                    clientA.send("RESERVE|DOG|Potato"));

            assertEquals("OK|Authenticated",
                    clientA.send("AUTH|" + TEST_KEY));

            assertEquals("OK|RESERVED|1001|Potato",
                    clientA.send("RESERVE|DOG|Potato"));

            // Client B is still unauthenticated.
            assertEquals("ERROR|Unauthorized",
                    clientB.send("GET|1001"));

            assertEquals("OK|DOG=2|CAT=2",
                    clientB.send("LIST"));
        }
    }

    @Test
    void testInvalidCommandsAndLargeRequests()
            throws Exception {

        try (TestClient client = new TestClient(port)) {

            assertEquals("ERROR|Unknown command",
                    client.send("HELLO"));

            assertEquals("OK|Authenticated",
                    client.send("AUTH|" + TEST_KEY));

            assertEquals("ERROR|Invalid input",
                    client.send("RESERVE|HORSE|Potato"));

            assertEquals("ERROR|Invalid input",
                    client.send("GET|abc"));

            assertEquals("ERROR|Request too long",
                    client.send("X".repeat(300)));
        }

        // The server should still accept another client.
        try (TestClient another = new TestClient(port)) {

            assertEquals("OK|DOG=3|CAT=2",
                    another.send("LIST"));
        }
    }

    @Test
    void testConcurrentSocketReservations()
            throws Exception {

        // Only one dog space remains.
        service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);

        int clientCount = 10;

        ExecutorService workers =
                Executors.newFixedThreadPool(clientCount);

        CountDownLatch ready =
                new CountDownLatch(clientCount);

        CountDownLatch start =
                new CountDownLatch(1);

        try {
            List<Future<String>> results = new ArrayList<>();

            for (int i = 0; i < clientCount; i++) {

                final int clientId = i + 1;

                results.add(workers.submit(() -> {

                    try (TestClient client =
                            new TestClient(port)) {

                        assertEquals("OK|Authenticated",
                                client.send("AUTH|" + TEST_KEY));

                        ready.countDown();

                        start.await();

                        return client.send(
                                "RESERVE|DOG|Pet" + clientId);
                    }
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Clients were not ready.");

            // Release all clients together.
            start.countDown();

            int successful = 0;
            int rejected = 0;

            for (Future<String> future : results) {

                String response =
                        future.get(10, TimeUnit.SECONDS);

                if (response.startsWith("OK|RESERVED|")) {
                    successful++;

                } else {
                    assertEquals(
                            "ERROR|No spaces available",
                            response);

                    rejected++;
                }
            }

            assertEquals(1, successful);
            assertEquals(9, rejected);

            assertEquals(0,
                    service.getAvailableSpaces(PetType.DOG));

            assertEquals(3,
                    service.getAllReservations().size());

            System.out.println(
                    "Concurrent TCP clients: " + clientCount);

            System.out.println(
                    "Successful reservations: " + successful);

            System.out.println(
                    "Rejected reservations: " + rejected);

        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void testTcpAndHttpUseSameState()
            throws Exception {

        ReservationHttpServer httpServer =
                new ReservationHttpServer(
                        service, 0, TEST_KEY);

        httpServer.start();

        try (TestClient client = new TestClient(port)) {

            client.send("AUTH|" + TEST_KEY);

            assertEquals("OK|RESERVED|1001|Potato",
                    client.send("RESERVE|DOG|Potato"));

            // Check TCP change through the real HTTP API.
            HttpClient httpClient = HttpClient.newHttpClient();

            String base = "http://localhost:"
                    + httpServer.getPort();

            HttpRequest get = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/resources"))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            get,
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());

            ObjectMapper mapper = new ObjectMapper();

            assertEquals(2,
                    mapper.readTree(response.body())
                            .get("DOG").asInt());

            // Now create a cat reservation through HTTP.
            HttpRequest post = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/reservations"))
                    .header("X-API-Key", TEST_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"petName\":\"Whiskers\","
                            + "\"petType\":\"CAT\"}"))
                    .build();

            HttpResponse<String> created =
                    httpClient.send(
                            post,
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(201, created.statusCode());

            // TCP must reflect the HTTP modification.
            assertEquals("OK|DOG=2|CAT=1",
                    client.send("LIST"));

        } finally {
            httpServer.stop();
        }
    }
}
