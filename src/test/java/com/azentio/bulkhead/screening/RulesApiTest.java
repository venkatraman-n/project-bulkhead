package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each of the four rule types fires on a crafted transaction and its reason code reaches the switch. */
@SpringBootTest
@AutoConfigureMockMvc
class RulesApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void amountThreshold_fires() throws Exception {
        screen("bank-a", payment().amount("10000.01"))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("AMOUNT_OVER_THRESHOLD")))
                .andExpect(jsonPath("$.riskScore").value(40))
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void highRiskCountry_fires() throws Exception {
        screen("bank-b", payment().country("KP"))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("HIGH_RISK_COUNTRY")))
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void newBeneficiaryWithHighAmount_firesOnlyOnFirstPayment() throws Exception {
        String account = "acct-" + UUID.randomUUID();
        screen("bank-a", payment().account(account).beneficiary("ben-1").amount("2500"))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("NEW_BENEFICIARY_HIGH_AMOUNT")));
        // Second payment to the same beneficiary: no longer new.
        screen("bank-a", payment().account(account).beneficiary("ben-1").amount("2500"))
                .andExpect(jsonPath("$.reasonCodes").value(not(hasItem("NEW_BENEFICIARY_HIGH_AMOUNT"))));
    }

    @Test
    void velocity_firesWhenAccountExceedsCountInWindow_andBanksUseTheirOwnWindows() throws Exception {
        String account = "acct-" + UUID.randomUUID();
        Instant start = Instant.parse("2026-11-17T07:00:00Z");
        // bank-c: VELOCITY_5M fires above 3 transactions in 5 minutes.
        for (int i = 0; i < 3; i++) {
            screen("bank-c", payment().account(account).at(start.plusSeconds(i * 30L)))
                    .andExpect(jsonPath("$.reasonCodes").value(not(hasItem("VELOCITY_5M"))));
        }
        screen("bank-c", payment().account(account).at(start.plusSeconds(120)))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("VELOCITY_5M")))
                .andExpect(jsonPath("$.decision").value("REVIEW"));

        // Same account token under bank-a: separate namespace, separate rule (VELOCITY_10M above 5).
        for (int i = 0; i < 5; i++) {
            screen("bank-a", payment().account(account).at(start.plusSeconds(i * 10L)))
                    .andExpect(jsonPath("$.reasonCodes").value(not(hasItem("VELOCITY_10M"))));
        }
        screen("bank-a", payment().account(account).at(start.plusSeconds(60)))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("VELOCITY_10M")));
    }

    @Test
    void futureDatedTransaction_isRejected_soItCannotPruneCurrentVelocityHistory() throws Exception {
        mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().at(Instant.now().plusSeconds(86_400)).json()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIMESTAMP_IN_FUTURE"));
    }

    @Test
    void unknownTenant_returns404() throws Exception {
        mvc.perform(post("/v1/tenants/bank-zz/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().json()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TENANT"));
    }

    @Test
    void invalidRequest_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().amount("-5").country("India").json()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ResultActions screen(String tenant, Payments tx) throws Exception {
        return mvc.perform(post("/v1/tenants/{tenant}/screenings", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(tx.json()))
                .andExpect(status().isOk());
    }
}
