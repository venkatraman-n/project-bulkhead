package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ScreeningRepository repository;

    @Test
    void sequentialRetry_returnsOriginalDecision_withoutSecondRecord() throws Exception {
        Payments tx = payment().amount("15000");
        String first = screen("bank-a", tx)
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();

        String retry = screen("bank-a", tx)
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(repository.countScreenings("bank-a", tx.transactionId())).isEqualTo(1);
    }

    @Test
    void sameTransactionId_withDifferentPayload_returns409() throws Exception {
        Payments tx = payment();
        screen("bank-a", tx);

        mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(tx.amount("999.99").json()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void sameTransactionId_underAnotherTenant_isScoredIndependently() throws Exception {
        // 4000 is below bank-a's 10000 amount threshold but above bank-c's 3000, so the banks disagree.
        Payments tx = payment().amount("4000");
        screen("bank-a", tx)
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.rulePackVersion").value("bank-a-2026.11.18-1"));
        screen("bank-c", tx)
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("AMOUNT_OVER_THRESHOLD"))
                .andExpect(jsonPath("$.rulePackVersion").value("bank-c-2026.11.18-1"));

        assertThat(repository.countScreenings("bank-a", tx.transactionId())).isEqualTo(1);
        assertThat(repository.countScreenings("bank-c", tx.transactionId())).isEqualTo(1);
    }

    private ResultActions screen(String tenant, Payments tx) throws Exception {
        return mvc.perform(post("/v1/tenants/{tenant}/screenings", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(tx.json()))
                .andExpect(status().isOk());
    }
}
