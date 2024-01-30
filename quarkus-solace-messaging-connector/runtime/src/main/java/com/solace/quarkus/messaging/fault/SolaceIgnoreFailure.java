package com.solace.quarkus.messaging.fault;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.mutiny.Uni;

public class SolaceIgnoreFailure implements SolaceFailureHandler {

    private final String channel;

    public SolaceIgnoreFailure(String channel) {
        this.channel = channel;
    }

    @Override
    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata) {
        SolaceLogging.log.messageSettled(channel, "ignored", reason.getMessage());
        return Uni.createFrom().voidItem().subscribeAsCompletionStage();
    }
}
