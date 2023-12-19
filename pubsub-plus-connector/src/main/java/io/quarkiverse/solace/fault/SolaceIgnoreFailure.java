package io.quarkiverse.solace.fault;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import io.quarkiverse.solace.i18n.SolaceLogging;
import io.quarkiverse.solace.incoming.SolaceInboundMessage;
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
