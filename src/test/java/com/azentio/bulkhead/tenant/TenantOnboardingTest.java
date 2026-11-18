package com.azentio.bulkhead.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding is configuration, not a release: the shipped rule packs plus a new bank-d.yaml in the config
 * directory, and bank-d scores with no code change. A broken file disables only that bank (503).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantOnboardingTest {

    private static final String BANK_D = """
            rulePackVersion: "bank-d-2026.11.18-1"
            rules:
              amountThreshold: { threshold: 750, score: 50 }
              highRiskCountry: { countries: [IR], score: 60 }
              newBeneficiaryHighAmount: { amount: 300, score: 40 }
              velocity:
                - { id: VELOCITY_2M, window: PT2M, maxCount: 2, score: 50 }
            bands: { reviewFrom: 40, declineFrom: 90 }
            degradation: { policy: REVIEW_ABOVE_AMOUNT, amount: 500 }
            """;

    private static final String BROKEN = """
            rulePackVersion: "bank-x-bad"
            rules:
              amountThreshold: { threshold: 100, score: 500 }
            bands: { reviewFrom: 80, declineFrom: 40 }
            """;

    @DynamicPropertySource
    static void tenantDir(DynamicPropertyRegistry registry) {
        registry.add("bulkhead.tenants.config-dir", TenantOnboardingTest::configDirWithNewBanks);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void addingBankDConfigFile_makesBankDScoreWithNoCodeChange() throws Exception {
        mvc.perform(post("/v1/tenants/bank-d/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().amount("800").json()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.reasonCodes").value(hasItem("AMOUNT_OVER_THRESHOLD")))
                .andExpect(jsonPath("$.rulePackVersion").value("bank-d-2026.11.18-1"));
    }

    @Test
    void existingBanksStillScore_whenAnotherBanksConfigIsBroken() throws Exception {
        mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().json()))
                .andExpect(status().isOk());
    }

    @Test
    void brokenConfig_disablesOnlyThatTenant_with503() throws Exception {
        mvc.perform(post("/v1/tenants/bank-x/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(payment().json()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_CONFIG_INVALID"));
    }

    private static String configDirWithNewBanks() {
        try {
            Path dir = Files.createTempDirectory("tenants");
            try (Stream<Path> shipped = Files.list(Path.of("config/tenants"))) {
                for (Path file : shipped.toList()) {
                    Files.copy(file, dir.resolve(file.getFileName()));
                }
            }
            Files.writeString(dir.resolve("bank-d.yaml"), BANK_D);
            Files.writeString(dir.resolve("bank-x.yaml"), BROKEN);
            return dir.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
