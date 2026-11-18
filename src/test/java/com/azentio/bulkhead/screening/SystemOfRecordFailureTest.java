package com.azentio.bulkhead.screening;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL slow/down: the pool times out and the switch gets a fast, explicit 503, never an unrecorded decision. */
@SpringBootTest
@AutoConfigureMockMvc
class SystemOfRecordFailureTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ScreeningRepository repository;

    @Test
    void databaseUnavailable_returns503_soTheSwitchAppliesItsFallback() throws Exception {
        when(repository.find(any(), any()))
                .thenThrow(new CannotGetJdbcConnectionException("Connection is not available, request timed out after 250ms"));

        mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().json()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SYSTEM_OF_RECORD_UNAVAILABLE"));
    }
}
