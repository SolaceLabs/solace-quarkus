package io.quarkiverse.solace.base;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.solace.messaging.MessagingService;

@ApplicationScoped
public class MessagingServiceProvider {

    static MessagingService messagingService;

    @Produces
    MessagingService messagingService() {
        return messagingService;
    }
}
