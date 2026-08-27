package com.solace.quarkus.messaging.fault;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.receiver.AcknowledgementSupport;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SettleMetadata;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.mutiny.Uni;

public class SolaceFail implements SolaceFailureHandler {
    private final String channel;
    // Supplier so the handler always targets the current receiver, which is
    // rebuilt on reconnect (DATAGO-141425).
    private final Supplier<AcknowledgementSupport> ackSupport;

    public SolaceFail(String channel, Supplier<AcknowledgementSupport> ackSupport) {
        this.channel = channel;
        this.ackSupport = ackSupport;
    }

    @Override
    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata) {
        MessageAcknowledgementConfiguration.Outcome outcome;
        if (metadata != null) {
            outcome = metadata.get(SettleMetadata.class)
                    .map(SettleMetadata::getOutcome)
                    .orElseGet(() -> MessageAcknowledgementConfiguration.Outcome.FAILED /* TODO get outcome from reason */);
        } else {
            outcome = MessageAcknowledgementConfiguration.Outcome.FAILED;
        }

        SolaceLogging.log.messageSettled(channel, outcome.toString().toLowerCase(), reason.getMessage());
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    AcknowledgementSupport support = ackSupport.get();
                    if (support != null) {
                        support.settle(msg.getMessage(), outcome);
                    }
                })
                .runSubscriptionOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}