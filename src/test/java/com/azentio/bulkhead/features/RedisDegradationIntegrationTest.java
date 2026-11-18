package com.azentio.bulkhead.features;

import com.azentio.bulkhead.support.Payments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.azentio.bulkhead.support.Payments.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Redis (Testcontainers): normal scoring, then degraded scoring while Redis is paused, then recovery.
 * Needs Docker, so it is excluded from `mvn test`; run with `mvn test -Pintegration`.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
        "bulkhead.features.store=redis",
        "bulkhead.features.breaker.minimum-calls=2",
        "bulkhead.features.breaker.sliding-window-size=4",
        "bulkhead.features.breaker.wait-in-open-ms=500",
        "bulkhead.features.breaker.half-open-calls=1"})
@AutoConfigureMockMvc
class RedisDegradationIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    StringRedisTemplate redis;

    @Test
    void redisVelocityIsIdempotent_byTransactionId() {
        RedisFeatureStore store = new RedisFeatureStore(redis);
        Duration window = Duration.ofMinutes(10);
        Instant now = Instant.parse("2026-11-18T06:00:00Z");
        FeatureQuery first = new FeatureQuery("bank-a", "acct-redis", "ben-1", "txn-1", now, List.of(window), window);

        assertThat(store.recordAndFetch(first).countFor(window)).isEqualTo(1);
        assertThat(store.recordAndFetch(first).countFor(window)).isEqualTo(1);       // duplicate: not counted twice
        assertThat(store.recordAndFetch(first).newBeneficiary()).isTrue();           // still "new" for its own txn
        FeatureQuery second = new FeatureQuery("bank-a", "acct-redis", "ben-1", "txn-2", now.plusSeconds(5),
                List.of(window), window);
        assertThat(store.recordAndFetch(second).countFor(window)).isEqualTo(2);
        assertThat(store.recordAndFetch(second).newBeneficiary()).isFalse();
        assertThat(redis.hasKey("bh:{bank-a:acct-redis}:vel")).isTrue();             // tenant-namespaced key
    }

    @Test
    void normal_thenDegradedWhileRedisIsPaused_thenRecovers() throws Exception {
        screen(payment()).andExpect(jsonPath("$.degraded").value(false));

        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                screen(payment().amount("2500"))
                        .andExpect(jsonPath("$.degraded").value(true))
                        .andExpect(jsonPath("$.missingFeatures[0]").value("velocity"));
                assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(150);
            }
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        // After the breaker's open wait, a probe succeeds and scoring is no longer degraded.
        boolean recovered = false;
        long giveUpAt = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!recovered && System.nanoTime() < giveUpAt) {
            Thread.sleep(200);
            String body = screen(payment()).andReturn().getResponse().getContentAsString();
            recovered = body.contains("\"degraded\":false");
        }
        assertThat(recovered).as("scoring recovers once Redis is back").isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions screen(Payments tx) throws Exception {
        return mvc.perform(post("/v1/tenants/bank-a/screenings").contentType(MediaType.APPLICATION_JSON)
                        .content(tx.json()))
                .andExpect(status().isOk());
    }
}
