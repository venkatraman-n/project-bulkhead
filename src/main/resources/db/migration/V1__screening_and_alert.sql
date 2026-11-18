-- System of record for scoring decisions. Portable across PostgreSQL and H2 (PostgreSQL mode).

CREATE TABLE screening (
    id                UUID          PRIMARY KEY,
    tenant_id         VARCHAR(32)   NOT NULL,
    transaction_id    VARCHAR(100)  NOT NULL,
    payload_hash      CHAR(64)      NOT NULL,          -- SHA-256 of the canonical request, detects id reuse
    account_token     VARCHAR(100)  NOT NULL,          -- already a token from the switch; masked on read APIs
    amount            NUMERIC(19,4) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    merchant_category VARCHAR(4),
    beneficiary_id    VARCHAR(100),
    country           CHAR(2)       NOT NULL,
    txn_timestamp     TIMESTAMP WITH TIME ZONE NOT NULL,
    decision          VARCHAR(10)   NOT NULL,
    risk_score        INT           NOT NULL,
    reason_codes      VARCHAR(500)  NOT NULL,
    rule_results      TEXT          NOT NULL,          -- JSON: every rule, threshold, observed value
    degraded          BOOLEAN       NOT NULL,
    missing_features  VARCHAR(200)  NOT NULL,
    rule_pack_version VARCHAR(64)   NOT NULL,
    evaluation_ms     INT           NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    -- The idempotency guarantee: one screening per tenant + transaction, even under concurrent retries.
    CONSTRAINT uk_screening_tenant_txn UNIQUE (tenant_id, transaction_id),
    CONSTRAINT ck_screening_score CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE TABLE alert (
    id             UUID         PRIMARY KEY,
    screening_id   UUID         NOT NULL,
    tenant_id      VARCHAR(32)  NOT NULL,
    transaction_id VARCHAR(100) NOT NULL,
    decision       VARCHAR(10)  NOT NULL,
    risk_score     INT          NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_alert_screening FOREIGN KEY (screening_id) REFERENCES screening (id),
    CONSTRAINT uk_alert_screening UNIQUE (screening_id)   -- at most one alert per screening
);

-- Investigator / case-management queue reads: open alerts per bank, newest first.
CREATE INDEX idx_alert_tenant_created ON alert (tenant_id, created_at);
