package io.quarkiverse.solace.runtime.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import com.solace.messaging.MessagingService;

@Liveness
@ApplicationScoped
public class SolaceHealthCheck implements HealthCheck {

    @Inject
    MessagingService solace;

    @Override
    public HealthCheckResponse call() {
        if (!solace.isConnected()) {
            return HealthCheckResponse.down("solace");
        }
        return HealthCheckResponse.up("solace");
    }
}
