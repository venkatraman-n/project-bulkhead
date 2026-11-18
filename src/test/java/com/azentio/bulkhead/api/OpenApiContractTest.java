package com.azentio.bulkhead.api;

import com.azentio.bulkhead.support.OpenApiContract;
import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real responses of both endpoints, validated against the committed docs/api/openapi.yaml.
 * Renaming or adding a response field without updating the spec fails this test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final String SCREENINGS = "/v1/tenants/{tenantId}/screenings";
    private static final String SCREENING = "/v1/tenants/{tenantId}/screenings/{transactionId}";
    private static final OpenApiContract CONTRACT = OpenApiContract.load(Path.of("docs/api/openapi.yaml"));

    @Autowired
    MockMvc mvc;

    @Test
    void specIsOpenApi3_withBothEndpoints_andAllRefsResolve() {
        assertThat(CONTRACT.spec().path("openapi").asText()).startsWith("3.");
        assertThat(CONTRACT.spec().path("paths").has(SCREENINGS)).isTrue();
        assertThat(CONTRACT.spec().path("paths").has(SCREENING)).isTrue();
        assertThat(CONTRACT.unresolvedRefs()).isEmpty();
    }

    @Test
    void postScreening_200_matchesContract() throws Exception {
        assertMatches(SCREENINGS, "post", postScreening("bank-a", payment().country("IR").beneficiary("ben-1")));
    }

    @Test
    void postScreening_409_and404_and400_matchContract() throws Exception {
        Payments tx = payment();
        postScreening("bank-a", tx);
        assertMatches(SCREENINGS, "post", postScreening("bank-a", tx.amount("1.23")));      // 409
        assertMatches(SCREENINGS, "post", postScreening("bank-unknown", payment()));        // 404
        assertMatches(SCREENINGS, "post", postScreening("bank-a", payment().country("x"))); // 400
    }

    @Test
    void getExplanation_200_and404_matchContract() throws Exception {
        Payments tx = payment().amount("12000").beneficiary("ben-2");
        postScreening("bank-a", tx);
        assertMatches(SCREENING, "get", mvc.perform(get("/v1/tenants/bank-a/screenings/{id}", tx.transactionId()))
                .andReturn().getResponse());
        assertMatches(SCREENING, "get", mvc.perform(get("/v1/tenants/bank-a/screenings/{id}", "no-such-txn"))
                .andReturn().getResponse());
    }

    @Test
    void renamedResponseField_isRejectedByTheContract() {
        String renamed = """
                {"tenantId":"bank-a","transactionId":"t1","decision":"APPROVE","score":0,"reasonCodes":[],
                 "degraded":false,"missingFeatures":[],"rulePackVersion":"v1","evaluationMs":3}""";
        List<String> errors = CONTRACT.validateResponse(SCREENINGS, "post", 200, "application/json", renamed);
        assertThat(errors).contains("$.riskScore is required but missing", "$.score is not declared in the contract");
    }

    private MockHttpServletResponse postScreening(String tenant, Payments tx) throws Exception {
        return mvc.perform(post("/v1/tenants/{tenant}/screenings", tenant)
                .contentType(MediaType.APPLICATION_JSON).content(tx.json())).andReturn().getResponse();
    }

    private static void assertMatches(String path, String method, MockHttpServletResponse response) throws Exception {
        String contentType = MediaType.parseMediaType(response.getContentType()).getSubtype().contains("problem")
                ? "application/problem+json" : "application/json";
        List<String> errors = CONTRACT.validateResponse(path, method, response.getStatus(), contentType,
                response.getContentAsString());
        assertThat(errors).as("%s %s -> %d: %s", method, path, response.getStatus(), response.getContentAsString())
                .isEmpty();
    }
}
