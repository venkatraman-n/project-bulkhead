package com.azentio.bulkhead.features;

/**
 * Velocity / behavioural features per tenant + account.
 *
 * One call both records the transaction and returns the features for it, so a remote store needs a single
 * round trip. Recording is idempotent by transactionId: a retried or duplicate transaction never counts twice.
 */
public interface FeatureStore {

    FeatureSnapshot recordAndFetch(FeatureQuery query);
}
