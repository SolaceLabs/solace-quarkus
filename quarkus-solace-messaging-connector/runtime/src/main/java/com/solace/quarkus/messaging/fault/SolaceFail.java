package com.solace.quarkus.messaging.fault;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.receiver.AcknowledgementSupport;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SettleMetadata;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.mutiny.Uni;

public class SolaceFail implements SolaceFailureHandler {
    private final String channel;
    private final AcknowledgementSupport ackSupport;

    public SolaceFail(String channel, AcknowledgementSupport ackSupport) {
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
                    if (ackSupport != null) {
                        ackSupport.settle(msg.getMessage(), outcome);
                    }
                })
                .runSubscriptionOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}
