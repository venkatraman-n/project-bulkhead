package com.azentio.bulkhead.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bulkhead.tenants")
public record TenantProperties(String configDir) {
}
