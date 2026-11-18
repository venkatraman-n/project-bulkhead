package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.ScreeningRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SHA-256 over a canonical form of the request. Two requests with the same transactionId are the
 * "same payload" only if every business field matches; 100 and 100.00 are treated as equal amounts.
 */
public final class PayloadHash {

    private static final char SEPARATOR = '\u001F'; // unit separator: cannot appear in validated fields

    private PayloadHash() {
    }

    public static String of(ScreeningRequest r) {
        String canonical = String.join(String.valueOf(SEPARATOR),
                r.transactionId(),
                r.accountToken(),
                r.amount().stripTrailingZeros().toPlainString(),
                r.currency(),
                Objects.toString(r.merchantCategory(), ""),
                Objects.toString(r.beneficiaryId(), ""),
                r.country(),
                r.timestamp().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
