package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.features.DelayingFeatureStore;
import com.azentio.bulkhead.features.FeatureStore;
import com.azentio.bulkhead.features.InMemoryFeatureStore;
import com.azentio.bulkhead.support.Payments;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The feature store is SLOW (500 ms per call), not just failing. Every bank must still get an answer well
 * inside its budget, marked degraded, with its own policy applied to the rules that could not run.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DegradedScoringTest {

    private static final Duration INJECTED_DELAY = Duration.ofMillis(500);

    @TestConfiguration
    static class SlowFeatureStore {
        @Bean
        @Primary
        FeatureStore slowFeatureStore() {
            return new DelayingFeatureStore(new InMemoryFeatureStore(), INJECTED_DELAY);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ScreeningRepository repository;
    @Autowired
    MeterRegistry meters;

    @Test
    void slowFeatureStore_decisionReturnsWellBeforeTheInjectedDelay_markedDegraded() throws Exception {
        screen("bank-a", payment()); // warm-up: first request pays JIT/class-loading cost

        Payments tx = payment().beneficiary("ben-9").amount("250");
        long start = System.nanoTime();
        screen("bank-a", tx)
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.missingFeatures", contains("velocity", "newBeneficiary")))
                .andExpect(jsonPath("$.decision").value("APPROVE"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isLessThan(150).isLessThan(INJECTED_DELAY.toMillis());
        Screening stored = repository.find("bank-a", tx.transactionId()).orElseThrow();
        assertThat(stored.degraded()).isTrue();
        assertThat(stored.missingFeatures()).containsExactly("velocity", "newBeneficiary");
    }

    /** Amounts are chosen below each bank's own amount-threshold rule, so only the degradation policy acts. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "bank-a, 100,  APPROVE,",                                // FAIL_OPEN: skip the rules, no extra action
            "bank-a, 9000, APPROVE,",                                // FAIL_OPEN has no amount threshold
            "bank-b, 1500, APPROVE,",                                // REVIEW_ABOVE_AMOUNT 2000: below
            "bank-b, 2500, REVIEW,  DEGRADED_REVIEW_ABOVE_AMOUNT",   // above
            "bank-c, 900,  APPROVE,",                                // DECLINE_ABOVE_AMOUNT 1000: below
            "bank-c, 1500, DECLINE, DEGRADED_DECLINE_ABOVE_AMOUNT",  // above
    })
    void eachBanksPolicy_appliesOnlyAboveItsAmount(String tenant, String amount, String decision, String reason)
            throws Exception {
        ResultActions result = screen(tenant, payment().amount(amount))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.decision").value(decision));
        if (reason == null) {
            result.andExpect(jsonPath("$.reasonCodes").value(not(hasItem(org.hamcrest.Matchers.startsWith("DEGRADED")))));
        } else {
            result.andExpect(jsonPath("$.reasonCodes").value(hasItem(reason)));
        }
    }

    @Test
    void degradedDecisions_areCountedPerTenant() throws Exception {
        double before = degradedCount("bank-b");
        screen("bank-b", payment());
        assertThat(degradedCount("bank-b")).isEqualTo(before + 1);
    }

    private double degradedCount(String tenant) {
        Counter counter = meters.find("bulkhead.screenings.degraded").tag("tenant", tenant).counter();
        return counter == null ? 0 : counter.count();
    }

    private ResultActions screen(String tenant, Payments tx) throws Exception {
        return mvc.perform(post("/v1/tenants/{tenant}/screenings", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(tx.json()))
                .andExpect(status().isOk());
    }
}
