package com.azentio.bulkhead.features;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class FeatureStoreConfig {

    @Bean
    FeatureStore featureStore(FeatureProperties properties, ObjectProvider<StringRedisTemplate> redis) {
        FeatureStore store = "redis".equalsIgnoreCase(properties.store())
                ? new RedisFeatureStore(redis.getObject())
                : new InMemoryFeatureStore();
        return properties.faultDelayMs() > 0
                ? new DelayingFeatureStore(store, Duration.ofMillis(properties.faultDelayMs()))
                : store;
    }

    @Bean
    ResilientFeatureClient resilientFeatureClient(FeatureStore featureStore, FeatureProperties properties) {
        return new ResilientFeatureClient(featureStore, properties.breaker());
    }
}
