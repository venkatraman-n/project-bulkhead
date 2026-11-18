package com.azentio.bulkhead.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * One bank's rule pack, loaded from config/tenants/{tenantId}.yaml.
 * Everything a bank can tune lives here, so onboarding a bank is a file, not a release.
 */
public record TenantConfig(
        @NotBlank String rulePackVersion,
        @NotNull @Valid Rules rules,
        @NotNull @Valid Bands bands,
        @Valid DegradationPolicy degradation) {

    public TenantConfig {
        if (degradation == null) {
            degradation = DegradationPolicy.failOpen();
        }
    }

    public record Rules(
            @NotNull @Valid AmountThreshold amountThreshold,
            @NotNull @Valid HighRiskCountry highRiskCountry,
            @NotNull @Valid NewBeneficiaryHighAmount newBeneficiaryHighAmount,
            @NotNull @Valid List<@Valid Velocity> velocity) {
    }

    public record AmountThreshold(@NotNull @DecimalMin("0") BigDecimal threshold, @Min(0) @Max(100) int score) {
    }

    public record HighRiskCountry(@NotEmpty Set<@Pattern(regexp = "[A-Z]{2}") String> countries,
                                  @Min(0) @Max(100) int score) {
    }

    public record NewBeneficiaryHighAmount(@NotNull @DecimalMin("0") BigDecimal amount, @Min(0) @Max(100) int score) {
    }

    /** "More than maxCount transactions for one account within window." The id doubles as the reason code. */
    public record Velocity(@NotBlank @Pattern(regexp = "VELOCITY_[A-Z0-9_]+") String id,
                           @NotNull Duration window,
                           @Min(1) int maxCount,
                           @Min(0) @Max(100) int score) {
    }

    /** Score-to-decision bands: score < reviewFrom -> APPROVE, < declineFrom -> REVIEW, else DECLINE. */
    public record Bands(@Min(1) @Max(100) int reviewFrom, @Min(1) @Max(100) int declineFrom) {
    }

    public record DegradationPolicy(@NotNull Mode policy, @DecimalMin("0") BigDecimal amount) {

        public enum Mode { FAIL_OPEN, REVIEW_ABOVE_AMOUNT, DECLINE_ABOVE_AMOUNT }

        public static DegradationPolicy failOpen() {
            return new DegradationPolicy(Mode.FAIL_OPEN, null);
        }
    }

    /** Longest velocity window: how long the feature store must keep an account's history. */
    public Duration longestVelocityWindow() {
        return rules.velocity().stream().map(Velocity::window).max(Duration::compareTo).orElse(Duration.ZERO);
    }
}
