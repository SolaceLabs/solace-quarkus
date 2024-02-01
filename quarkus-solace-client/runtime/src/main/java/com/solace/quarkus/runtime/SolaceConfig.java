package com.solace.quarkus.runtime;

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
     * Any extra parameters to pass to the Solace client.
     * <br/>
     * <br/>
     * <br/>
     * Refer to
     * <a href=
     * "https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/constant-values.html#com.solace.messaging.config.SolaceProperties.AuthenticationProperties.SCHEME">AuthenticationProperties</a>
     * and
     * <a href=
     * "https://docs.solace.com/API-Developer-Online-Ref-Documentation/pubsubplus-java/constant-values.html#com.solace.messaging.config.SolaceProperties.TransportLayerProperties.COMPRESSION_LEVEL">TransportLayerProperties</a>
     * for more configuration options
     * <br/>
     * <br/>
     * <br/>
     * Example: To configure compression `quarkus.solace.transport.compression-level`
     */
    @WithParentName
    Map<String, String> extra();

}
