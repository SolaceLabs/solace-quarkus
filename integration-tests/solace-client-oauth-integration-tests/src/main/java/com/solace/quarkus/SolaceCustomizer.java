package com.solace.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

import com.solace.messaging.MessagingServiceClientBuilder;

@ApplicationScoped
public class SolaceCustomizer implements MessagingServiceClientCustomizer {
    @Override
    public MessagingServiceClientBuilder customize(MessagingServiceClientBuilder builder) {
        return builder;
    }
}
