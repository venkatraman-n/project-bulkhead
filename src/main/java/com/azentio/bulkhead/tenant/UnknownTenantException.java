package com.azentio.bulkhead.tenant;

import com.azentio.bulkhead.api.error.ApiException;
import org.springframework.http.HttpStatus;

public class UnknownTenantException extends ApiException {

    public UnknownTenantException(String tenantId) {
        super(HttpStatus.NOT_FOUND, "UNKNOWN_TENANT", "Tenant '" + tenantId + "' is not configured");
    }
}
