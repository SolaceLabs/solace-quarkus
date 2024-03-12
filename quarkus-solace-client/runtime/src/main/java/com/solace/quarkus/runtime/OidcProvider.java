package com.solace.quarkus.runtime;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.SolaceProperties;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class OidcProvider {

    @ConfigProperty(name = "quarkus.solace.oidc.refresh.interval", defaultValue = "60s")
    Duration duration;

    @Inject
    OidcClient client;

    private volatile Tokens lastToken;
    private MessagingService service;

    Tokens getToken() {
        Tokens firstToken = client.getTokens().await().indefinitely();
        lastToken = firstToken;
        return firstToken;
    }

    void init(MessagingService service) {
        this.service = service;
    }

    void startup(@Observes StartupEvent event) {
        Multi.createFrom().ticks().every(duration)
                .emitOn(Infrastructure.getDefaultWorkerPool())
                //                .filter(aLong -> {
                //                    if (lastToken.isAccessTokenWithinRefreshInterval()) {
                //                        return true;
                //                    } else
                //                        return false;
                //                })
                .call(() -> client.getTokens().invoke(tokens -> {
                    lastToken = tokens;
                }))
                .invoke(() -> service.updateProperty(SolaceProperties.AuthenticationProperties.SCHEME_OAUTH2_ACCESS_TOKEN,
                        lastToken.getAccessToken()))
                .subscribe().with(aLong -> {
                });
    }
}
