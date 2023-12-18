package io.quarkiverse.solace.deployment;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithParentName;

@ConfigMapping(prefix = "quarkus.solace")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface SolaceBuildTimeConfig {
    /**
     * Metrics configuration.
     */
    MetricsConfig metrics();

    /**
     * Health configuration.
     */
    HealthConfig health();

    /**
     * Default Dev services configuration.
     */
    @WithParentName
    DevServiceConfiguration defaultDevService();

    @ConfigGroup
    public interface DevServiceConfiguration {
        /**
         * Configuration for DevServices
         * <p>
         * DevServices allows Quarkus to automatically start Solace in dev and test mode.
         */
        DevServicesConfig devservices();
    }

    /**
     * Metrics configuration.
     */
    interface MetricsConfig {
        /**
         * Whether a metrics is enabled in case the micrometer is present.
         */
        @WithDefault("true")
        boolean enabled();
    }

    /**
     * Health configuration.
     */
    interface HealthConfig {
        /**
         * Whether the liveness health check should be exposed if the smallrye-health extension is present.
         */
        @WithDefault("true")
        boolean enabled();
    }
}
