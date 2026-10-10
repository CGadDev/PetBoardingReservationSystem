package com.petboarding.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.petboarding.model.PetType;
import com.petboarding.repository.InMemoryReservationRepository;

public class ConcurrencyTest {

    @Test
    void testConcurrentReservations() throws Exception {

        ReservationService service = new ReservationService(
                new InMemoryReservationRepository()
        );

        // Reserve two of the three dog spaces.
        service.reserve("Potato", PetType.DOG);
        service.reserve("Buddy", PetType.DOG);

        // Only one dog space remains.
        assertEquals(1, service.getAvailableSpaces(PetType.DOG));

        int clientCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(clientCount);

        CountDownLatch ready = new CountDownLatch(clientCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = new ArrayList<>();

            // Simulate ten clients attempting reservations.
            for (int i = 0; i < clientCount; i++) {

                final int clientId = i + 1;

                results.add(executor.submit(() -> {

                    ready.countDown();
                    start.await();

                    try {
                        service.reserve(
                                "Pet" + clientId,
                                PetType.DOG
                        );

                        return true;

                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }

            // Wait until all clients are ready.
            assertTrue(ready.await(10, TimeUnit.SECONDS));

            // Release all clients to compete for the space.
            start.countDown();

            int successfulReservations = 0;

            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successfulReservations++;
                }
            }

            // Only one client should succeed.
            assertEquals(1, successfulReservations);

            // No dog spaces should remain.
            assertEquals(0, service.getAvailableSpaces(PetType.DOG));

            // Exactly three reservations should exist.
            assertEquals(3, service.getAllReservations().size());

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
