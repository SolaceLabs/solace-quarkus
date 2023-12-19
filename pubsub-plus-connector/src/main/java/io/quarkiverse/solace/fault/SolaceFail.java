package io.quarkiverse.solace.fault;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.receiver.AcknowledgementSupport;

import io.quarkiverse.solace.i18n.SolaceLogging;
import io.quarkiverse.solace.incoming.SettleMetadata;
import io.quarkiverse.solace.incoming.SolaceInboundMessage;
import io.smallrye.mutiny.Uni;

public class SolaceFail implements SolaceFailureHandler {
    private final String channel;
    private final AcknowledgementSupport ackSupport;

    private final MessagingService solace;

    public SolaceFail(String channel, AcknowledgementSupport ackSupport, MessagingService solace) {
        this.channel = channel;
        this.ackSupport = ackSupport;
        this.solace = solace;
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
                .invoke(() -> ackSupport.settle(msg.getMessage(), outcome))
                .runSubscriptionOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}
