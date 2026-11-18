package com.azentio.bulkhead.support;

import java.time.Instant;
import java.util.UUID;

/** Builds synthetic screening request bodies. Defaults are low-risk; tests override one field at a time. */
public final class Payments {

    private String transactionId = "txn-" + UUID.randomUUID();
    private String accountToken = "acct-" + UUID.randomUUID();
    private String amount = "100.00";
    private String currency = "INR";
    private String merchantCategory = "5411";
    private String beneficiaryId = null;
    private String country = "IN";
    private Instant timestamp = Instant.parse("2026-11-18T06:00:00Z");

    public static Payments payment() {
        return new Payments();
    }

    public Payments transactionId(String value) { transactionId = value; return this; }
    public Payments account(String value) { accountToken = value; return this; }
    public Payments amount(String value) { amount = value; return this; }
    public Payments beneficiary(String value) { beneficiaryId = value; return this; }
    public Payments country(String value) { country = value; return this; }
    public Payments at(Instant value) { timestamp = value; return this; }

    public String transactionId() { return transactionId; }
    public String accountToken() { return accountToken; }

    public String json() {
        return """
                {"transactionId":"%s","accountToken":"%s","amount":%s,"currency":"%s","merchantCategory":"%s",%s"country":"%s","timestamp":"%s"}"""
                .formatted(transactionId, accountToken, amount, currency, merchantCategory,
                        beneficiaryId == null ? "" : "\"beneficiaryId\":\"" + beneficiaryId + "\",",
                        country, timestamp);
    }
}
