package io.quarkiverse.solace.runtime;

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import com.solace.messaging.MessagingService;
import com.solace.messaging.MessagingServiceClientBuilder;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.config.profile.ConfigurationProfile;

import io.quarkiverse.solace.MessagingServiceClientCustomizer;
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

                MessagingServiceClientBuilder builder = MessagingService.builder(ConfigurationProfile.V1)
                        .fromProperties(properties);

                Instance<MessagingServiceClientCustomizer> reference = context.getInjectedReference(CUSTOMIZER);

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

                var tmp = service;
                shutdown.addShutdownTask(() -> {
                    if (tmp.isConnected()) {
                        tmp.disconnect();
                    }
                });
                return service.connect();
            }
        };
    }

}
