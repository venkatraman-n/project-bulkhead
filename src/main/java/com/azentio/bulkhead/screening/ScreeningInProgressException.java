package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.error.ApiException;
import org.springframework.http.HttpStatus;

/** A concurrent duplicate waited for the owner's decision and the owner has not finished: retry shortly. */
public class ScreeningInProgressException extends ApiException {

    public ScreeningInProgressException(String tenantId, String transactionId) {
        super(HttpStatus.CONFLICT, "SCREENING_IN_PROGRESS",
                "Transaction '" + transactionId + "' for tenant '" + tenantId + "' is still being screened; retry");
    }
}
