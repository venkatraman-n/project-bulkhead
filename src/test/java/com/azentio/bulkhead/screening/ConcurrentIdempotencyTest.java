package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.screening.rules.RuleEngine;
import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Switches retry, sometimes in parallel. Real HTTP, real threads, released together by a start gate:
 * the transaction is scored exactly once, one screening and one alert exist, every caller gets the same answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentIdempotencyTest {

    private static final int THREADS = 16;

    @LocalServerPort
    int port;
    @Autowired
    ScreeningRepository repository;
    @MockitoSpyBean
    RuleEngine ruleEngine;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void warmUp() throws Exception {
        send("bank-a", payment()); // first request pays JIT/class-loading cost, so the race is about logic not startup
    }

    @Test
    void concurrentDuplicates_areScoredOnce_storeOneScreeningAndAlert_andReturnIdenticalResponses() throws Exception {
        // High-risk country -> REVIEW for bank-a, so an alert is created and its uniqueness is tested too.
        Payments tx = payment().country("IR");
        List<HttpResponse<String>> responses = race(List.of(tx), THREADS);

        List<String> bodies = responses.stream().map(HttpResponse::body).toList();
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(bodies).hasSize(THREADS).containsOnly(bodies.get(0));
        assertThat(bodies.get(0)).contains("\"decision\":\"REVIEW\"").contains("HIGH_RISK_COUNTRY");

        verify(ruleEngine, times(1)).evaluate(any(), argThat(r -> r.transactionId().equals(tx.transactionId())), any());
        assertThat(repository.countScreenings("bank-a", tx.transactionId())).isEqualTo(1);
        assertThat(repository.countAlerts("bank-a", tx.transactionId())).isEqualTo(1);
    }

    @Test
    void concurrentConflictingPayloads_oneWins_othersGet409_andLosersTouchNoFeatures() throws Exception {
        String txnId = "txn-conflict-" + System.nanoTime();
        Instant at = Instant.parse("2026-11-17T08:00:00Z");
        Payments first = payment().transactionId(txnId).account("acct-first-" + txnId).at(at);
        Payments second = payment().transactionId(txnId).account("acct-second-" + txnId).at(at);

        List<HttpResponse<String>> responses = race(List.of(first, second), THREADS);

        long ok = responses.stream().filter(r -> r.statusCode() == 200).count();
        long conflicts = responses.stream().filter(r -> r.statusCode() == 409).count();
        assertThat(ok + conflicts).isEqualTo(THREADS);
        assertThat(ok).isPositive();
        assertThat(conflicts).isPositive();
        verify(ruleEngine, times(1)).evaluate(any(), argThat(r -> r.transactionId().equals(txnId)), any());
        assertThat(repository.countScreenings("bank-a", txnId)).isEqualTo(1);

        // The losing account's velocity history was never written: its next transaction counts 1, not 2.
        String winnerAccount = repository.find("bank-a", txnId).orElseThrow().accountToken();
        Payments loser = winnerAccount.equals(first.accountToken()) ? second : first;
        Payments next = payment().account(loser.accountToken()).at(at.plusSeconds(10));
        send("bank-a", next);
        String explanation = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/tenants/bank-a/screenings/"
                + next.transactionId())).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(explanation).contains("{\"rule\":\"VELOCITY_10M\",\"fired\":false,\"skipped\":false,"
                + "\"threshold\":\"> 5 in PT10M\",\"observed\":\"1\"");
    }

    /** Sends every payload THREADS/payloads.size() times, all released at the same instant. */
    private List<HttpResponse<String>> race(List<Payments> payloads, int threads) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Payments tx = payloads.get(i % payloads.size());
                calls.add(pool.submit(() -> {
                    startGate.await();
                    return send("bank-a", tx);
                }));
            }
            startGate.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> call : calls) {
                responses.add(call.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private HttpResponse<String> send(String tenant, Payments tx) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/tenants/" + tenant + "/screenings"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(tx.json()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
