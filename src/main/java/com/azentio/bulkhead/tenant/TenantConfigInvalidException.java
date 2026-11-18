package com.azentio.bulkhead.tenant;

import com.azentio.bulkhead.api.error.ApiException;
import org.springframework.http.HttpStatus;

/** The bank has a config file but it failed validation: tell the switch to use its own fallback. */
public class TenantConfigInvalidException extends ApiException {

    public TenantConfigInvalidException(String tenantId) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TENANT_CONFIG_INVALID",
                "Rule pack for tenant '" + tenantId + "' failed validation; scoring is disabled for this tenant");
    }
}
