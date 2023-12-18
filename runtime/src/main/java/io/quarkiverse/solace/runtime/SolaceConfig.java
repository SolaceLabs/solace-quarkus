package io.quarkiverse.solace.runtime;

import java.util.Map;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

@ConfigMapping(prefix = "quarkus.solace")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface SolaceConfig {

    /**
     * The Solace host (hostname:port)
     */
    String host();

    /**
     * The Solace VPN
     */
    String vpn();

    /**
     * Any extra parameters to pass to the Solace client
     */
    @WithParentName
    Map<String, String> extra();

}
