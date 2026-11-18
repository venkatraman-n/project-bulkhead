package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.screening.rules.RuleResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC on the hot path: two statements per scored transaction, no ORM overhead or lazy loading.
 * Duplicate (tenant_id, transaction_id) inserts surface as Spring's DuplicateKeyException.
 */
@Repository
public class ScreeningRepository {

    private static final TypeReference<List<RuleResult>> RULE_RESULTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ScreeningRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Screening> find(String tenantId, String transactionId) {
        return jdbc.sql("SELECT * FROM screening WHERE tenant_id = :tenant AND transaction_id = :txn")
                .param("tenant", tenantId)
                .param("txn", transactionId)
                .query(this::map)
                .optional();
    }

    public void insert(Screening s) {
        jdbc.sql("""
                        INSERT INTO screening (id, tenant_id, transaction_id, payload_hash, account_token, amount, currency,
                            merchant_category, beneficiary_id, country, txn_timestamp, decision, risk_score, reason_codes,
                            rule_results, degraded, missing_features, rule_pack_version, evaluation_ms, created_at)
                        VALUES (:id, :tenant, :txn, :hash, :account, :amount, :currency, :mcc, :beneficiary, :country,
                            :txnTs, :decision, :score, :reasons, :rules, :degraded, :missing, :version, :evalMs, :createdAt)
                        """)
                .param("id", s.id())
                .param("tenant", s.tenantId())
                .param("txn", s.transactionId())
                .param("hash", s.payloadHash())
                .param("account", s.accountToken())
                .param("amount", s.amount())
                .param("currency", s.currency())
                .param("mcc", s.merchantCategory())
                .param("beneficiary", s.beneficiaryId())
                .param("country", s.country())
                .param("txnTs", Timestamp.from(s.transactionTimestamp()))
                .param("decision", s.decision().name())
                .param("score", s.riskScore())
                .param("reasons", String.join(",", s.reasonCodes()))
                .param("rules", toJson(s.ruleResults()))
                .param("degraded", s.degraded())
                .param("missing", String.join(",", s.missingFeatures()))
                .param("version", s.rulePackVersion())
                .param("evalMs", s.evaluationMs())
                .param("createdAt", Timestamp.from(s.createdAt()))
                .update();
    }

    public void insertAlert(Screening s) {
        jdbc.sql("""
                        INSERT INTO alert (id, screening_id, tenant_id, transaction_id, decision, risk_score, status, created_at)
                        VALUES (:id, :screening, :tenant, :txn, :decision, :score, 'OPEN', :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("screening", s.id())
                .param("tenant", s.tenantId())
                .param("txn", s.transactionId())
                .param("decision", s.decision().name())
                .param("score", s.riskScore())
                .param("createdAt", Timestamp.from(s.createdAt()))
                .update();
    }

    public long countScreenings(String tenantId, String transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM screening WHERE tenant_id = :tenant AND transaction_id = :txn")
                .param("tenant", tenantId).param("txn", transactionId).query(Long.class).single();
    }

    public long countAlerts(String tenantId, String transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM alert WHERE tenant_id = :tenant AND transaction_id = :txn")
                .param("tenant", tenantId).param("txn", transactionId).query(Long.class).single();
    }

    private Screening map(ResultSet rs, int row) throws SQLException {
        return new Screening(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("transaction_id"),
                rs.getString("payload_hash"),
                rs.getString("account_token"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("merchant_category"),
                rs.getString("beneficiary_id"),
                rs.getString("country"),
                rs.getTimestamp("txn_timestamp").toInstant(),
                Decision.valueOf(rs.getString("decision")),
                rs.getInt("risk_score"),
                split(rs.getString("reason_codes")),
                fromJson(rs.getString("rule_results")),
                rs.getBoolean("degraded"),
                split(rs.getString("missing_features")),
                rs.getString("rule_pack_version"),
                rs.getInt("evaluation_ms"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }

    private String toJson(List<RuleResult> results) {
        try {
            return json.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise rule results", e);
        }
    }

    private List<RuleResult> fromJson(String value) {
        try {
            return json.readValue(value, RULE_RESULTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt rule_results column", e);
        }
    }
}
