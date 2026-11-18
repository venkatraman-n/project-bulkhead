package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switches retry, sometimes in parallel. Real HTTP, real threads, released together by a start gate:
 * exactly one screening and one alert may exist, and every caller must get the same answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentIdempotencyTest {

    private static final int THREADS = 16;

    @LocalServerPort
    int port;
    @Autowired
    ScreeningRepository repository;

    @Test
    void concurrentDuplicates_storeOneScreeningAtMostOneAlert_andReturnIdenticalResponses() throws Exception {
        // High-risk country -> REVIEW for bank-a, so an alert is created and its uniqueness is tested too.
        Payments tx = payment().country("IR");
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/tenants/bank-a/screenings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(tx.json()))
                .build();

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                calls.add(pool.submit(() -> {
                    startGate.await();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            startGate.countDown();

            List<String> bodies = new ArrayList<>();
            for (Future<HttpResponse<String>> call : calls) {
                HttpResponse<String> response = call.get(30, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                bodies.add(response.body());
            }
            assertThat(bodies).hasSize(THREADS).containsOnly(bodies.get(0));
            assertThat(bodies.get(0)).contains("\"decision\":\"REVIEW\"").contains("HIGH_RISK_COUNTRY");
        }

        assertThat(repository.countScreenings("bank-a", tx.transactionId())).isEqualTo(1);
        assertThat(repository.countAlerts("bank-a", tx.transactionId())).isEqualTo(1);
    }
}
