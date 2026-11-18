-- Ownership of a tenant + transaction BEFORE any scoring side effect (feature writes, rule evaluation, metrics).
-- The first request to insert the claim scores; concurrent duplicates on any instance wait for its stored
-- decision, and a different payload is rejected with 409 without touching the feature store.
CREATE TABLE screening_claim (
    tenant_id      VARCHAR(32)  NOT NULL,
    transaction_id VARCHAR(100) NOT NULL,
    payload_hash   CHAR(64)     NOT NULL,
    claimed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_screening_claim PRIMARY KEY (tenant_id, transaction_id)
);
