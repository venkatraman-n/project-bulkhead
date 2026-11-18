package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.error.ApiException;
import org.springframework.http.HttpStatus;

/** Same tenant + transactionId seen before with a different payload: a switch bug, never a retry. */
public class IdempotencyConflictException extends ApiException {

    public IdempotencyConflictException(String tenantId, String transactionId) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Transaction '" + transactionId + "' was already screened for tenant '" + tenantId
                        + "' with a different payload");
    }
}
