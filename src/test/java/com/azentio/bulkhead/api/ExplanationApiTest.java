package com.azentio.bulkhead.api;

import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExplanationApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void explanation_showsFiredRulesThresholdsObservedValues_versionAndMaskedToken() throws Exception {
        String token = "acct-9876543210";
        Payments tx = payment().account(token).amount("15000").country("IR").beneficiary("ben-new");
        screen("bank-a", tx);

        String body = mvc.perform(get("/v1/tenants/bank-a/screenings/{id}", tx.transactionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountToken").value("****3210"))
                .andExpect(jsonPath("$.decision").value("DECLINE"))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.missingFeatures").isEmpty())
                .andExpect(jsonPath("$.rulePackVersion").value("bank-a-2026.11.18-1"))
                .andExpect(jsonPath("$.screenedAt").isNotEmpty())
                .andExpect(jsonPath("$.transactionTimestamp").value("2026-11-18T06:00:00Z"))
                .andExpect(jsonPath("$.rules[?(@.rule=='AMOUNT_OVER_THRESHOLD')].fired").value(true))
                .andExpect(jsonPath("$.rules[?(@.rule=='AMOUNT_OVER_THRESHOLD')].threshold").value("> 10000"))
                .andExpect(jsonPath("$.rules[?(@.rule=='AMOUNT_OVER_THRESHOLD')].observed").value("15000"))
                .andExpect(jsonPath("$.rules[?(@.rule=='HIGH_RISK_COUNTRY')].fired").value(true))
                .andExpect(jsonPath("$.rules[?(@.rule=='HIGH_RISK_COUNTRY')].observed").value("IR"))
                .andExpect(jsonPath("$.rules[?(@.rule=='NEW_BENEFICIARY_HIGH_AMOUNT')].fired").value(true))
                .andExpect(jsonPath("$.rules[?(@.rule=='VELOCITY_10M')].fired").value(false))
                .andExpect(jsonPath("$.rules[?(@.rule=='VELOCITY_10M')].observed").value("1"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(token).doesNotContain("9876543210");
    }

    @Test
    void anotherTenant_cannotReadTheScreening_404() throws Exception {
        Payments tx = payment();
        screen("bank-a", tx);

        mvc.perform(get("/v1/tenants/bank-b/screenings/{id}", tx.transactionId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"));
    }

    @Test
    void unknownTenantOrTransaction_404() throws Exception {
        mvc.perform(get("/v1/tenants/bank-zz/screenings/{id}", "txn-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TENANT"));
        mvc.perform(get("/v1/tenants/bank-a/screenings/{id}", "never-screened"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"));
    }

    private void screen(String tenant, Payments tx) throws Exception {
        mvc.perform(post("/v1/tenants/{tenant}/screenings", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(tx.json()))
                .andExpect(status().isOk());
    }
}
