package com.azentio.bulkhead.features;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeatureStoreConfig {

    @Bean
    FeatureStore featureStore() {
        return new InMemoryFeatureStore();
    }
}
