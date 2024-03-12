package com.solace.quarkus.runtime;

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import com.solace.messaging.MessagingService;
import com.solace.messaging.MessagingServiceClientBuilder;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;
import com.solace.quarkus.MessagingServiceClientCustomizer;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class SolaceRecorder {

    private static final TypeLiteral<Instance<MessagingServiceClientCustomizer>> CUSTOMIZER = new TypeLiteral<>() {
    };

    public Function<SyntheticCreationalContext<MessagingService>, MessagingService> init(SolaceConfig config,
            ShutdownContext shutdown) {
        return new Function<>() {
            @Override
            public MessagingService apply(SyntheticCreationalContext<MessagingService> context) {
                Properties properties = new Properties();
                properties.put(SolaceProperties.TransportLayerProperties.HOST, config.host());
                properties.put(SolaceProperties.ServiceProperties.VPN_NAME, config.vpn());
                for (Map.Entry<String, String> entry : config.extra().entrySet()) {
                    properties.put(entry.getKey(), entry.getValue());
                    if (!entry.getKey().startsWith("solace.messaging.")) {
                        properties.put("solace.messaging." + entry.getKey(), entry.getValue());
                    }
                }

                Instance<MessagingServiceClientCustomizer> reference = context.getInjectedReference(CUSTOMIZER);
                OidcProvider oidcProvider = context.getInjectedReference(OidcProvider.class);

                String authScheme = config.extra().get("authentication.scheme");

                if (oidcProvider != null && authScheme != null && authScheme.equals("AUTHENTICATION_SCHEME_OAUTH2")) {
                    properties.put(SolaceProperties.AuthenticationProperties.SCHEME_OAUTH2_ACCESS_TOKEN,
                            oidcProvider.getToken().getAccessToken());
                }

                MessagingServiceClientBuilder builder = MessagingService.builder(ConfigurationProfile.V1)
                        .fromProperties(properties);
                MessagingService service;
                if (reference.isUnsatisfied()) {
                    service = builder.build();
                } else {
                    if (!reference.isResolvable()) {
                        throw new IllegalStateException("Multiple MessagingServiceClientCustomizer instances found");
                    } else {
                        service = reference.get().customize(builder).build();
                    }
                }

                oidcProvider.init(service);
                var tmp = service;
                shutdown.addLastShutdownTask(() -> {
                    if (tmp.isConnected()) {
                        tmp.disconnect();
                    }
                });

                return service.connect();
            }
        };
    }

}
