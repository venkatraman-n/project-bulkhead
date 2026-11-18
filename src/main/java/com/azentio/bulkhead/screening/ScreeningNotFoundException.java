package com.azentio.bulkhead.screening;

import com.azentio.bulkhead.api.error.ApiException;
import org.springframework.http.HttpStatus;

/** Also returned when the transaction exists under another tenant: never reveal other banks' data. */
public class ScreeningNotFoundException extends ApiException {

    public ScreeningNotFoundException(String tenantId, String transactionId) {
        super(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND",
                "No screening for transaction '" + transactionId + "' in tenant '" + tenantId + "'");
    }
}
