package com.azentio.bulkhead.tenant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads one rule pack per bank from the config directory at startup (file name = tenant id).
 *
 * A broken file must not take the other banks down: it is logged and that tenant answers 503
 * (TENANT_CONFIG_INVALID) until the file is fixed, while valid tenants keep scoring.
 */
@Component
public class TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);
    static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    /** Longest velocity window a bank may configure; bounds Redis memory (docs/architecture/nfr.md section 2.4). */
    static final Duration MAX_WINDOW = Duration.ofDays(7);

    private final Map<String, TenantConfig> tenants;
    private final Map<String, String> invalidTenants;

    public TenantRegistry(TenantProperties properties, Validator validator) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        Map<String, TenantConfig> loaded = new TreeMap<>();
        Map<String, String> invalid = new TreeMap<>();

        Path configDir = Path.of(properties.configDir());
        try (Stream<Path> files = Files.list(configDir)) {
            for (Path file : files.filter(TenantRegistry::isYaml).sorted().toList()) {
                String tenantId = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
                if (!TENANT_ID.matcher(tenantId).matches()) {
                    log.error("Ignoring tenant file {}: file name is not a valid tenant id", file);
                    continue;
                }
                try {
                    TenantConfig config = yaml.readValue(file.toFile(), TenantConfig.class);
                    String problem = validate(config, validator);
                    if (problem == null) {
                        loaded.put(tenantId, config);
                        log.info("Loaded tenant {} rule pack {}", tenantId, config.rulePackVersion());
                    } else {
                        invalid.put(tenantId, problem);
                        log.error("Tenant {} config INVALID, tenant disabled: {}", tenantId, problem);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    invalid.put(tenantId, e.getMessage());
                    log.error("Tenant {} config UNREADABLE, tenant disabled: {}", tenantId, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read tenant config dir " + configDir.toAbsolutePath(), e);
        }
        this.tenants = Map.copyOf(loaded);
        this.invalidTenants = Map.copyOf(invalid);
    }

    /** @throws UnknownTenantException 404 when no file exists; @throws TenantConfigInvalidException 503 when it is broken. */
    public TenantConfig require(String tenantId) {
        TenantConfig config = tenants.get(tenantId);
        if (config != null) {
            return config;
        }
        if (invalidTenants.containsKey(tenantId)) {
            throw new TenantConfigInvalidException(tenantId);
        }
        throw new UnknownTenantException(tenantId);
    }

    public Set<String> tenantIds() {
        return tenants.keySet();
    }

    private static String validate(TenantConfig config, Validator validator) {
        Set<ConstraintViolation<TenantConfig>> violations = validator.validate(config);
        if (!violations.isEmpty()) {
            return violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
        }
        if (config.bands().reviewFrom() >= config.bands().declineFrom()) {
            return "bands.reviewFrom must be lower than bands.declineFrom";
        }
        var degradation = config.degradation();
        if (degradation.policy() != TenantConfig.DegradationPolicy.Mode.FAIL_OPEN && degradation.amount() == null) {
            return "degradation.amount is required for " + degradation.policy();
        }
        for (TenantConfig.Velocity velocity : config.rules().velocity()) {
            if (velocity.window().isNegative() || velocity.window().isZero() || velocity.window().compareTo(MAX_WINDOW) > 0) {
                return "velocity " + velocity.id() + " window must be between PT1S and " + MAX_WINDOW;
            }
        }
        long distinctIds = config.rules().velocity().stream().map(TenantConfig.Velocity::id).distinct().count();
        if (distinctIds != config.rules().velocity().size()) {
            return "velocity rule ids must be unique";
        }
        return null;
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }
}
