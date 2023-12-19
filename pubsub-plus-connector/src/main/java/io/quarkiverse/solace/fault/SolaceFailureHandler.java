package io.quarkiverse.solace.fault;

import static io.quarkiverse.solace.i18n.SolaceExceptions.ex;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import io.quarkiverse.solace.incoming.SolaceInboundMessage;

public interface SolaceFailureHandler {

    enum Strategy {
        IGNORE,
        FAIL,
        DISCARD,
        ERROR_TOPIC;

        public static Strategy from(String s) {
            if (s == null || s.equalsIgnoreCase("ignore")) {
                return IGNORE;
            }
            if (s.equalsIgnoreCase("fail")) {
                return FAIL;
            }
            if (s.equalsIgnoreCase("discard")) {
                return DISCARD;
            }
            if (s.equalsIgnoreCase("error_topic")) {
                return ERROR_TOPIC;
            }

            throw ex.illegalArgumentUnknownFailureStrategy(s);
        }
    }

    //    private final String channel;
    //    private final AcknowledgementSupport ackSupport;
    //
    //    private final MessagingService solace;

    //    public SolaceFailureHandler(String channel, AcknowledgementSupport ackSupport, MessagingService solace) {
    //        this.channel = channel;
    //        this.ackSupport = ackSupport;
    //        this.solace = solace;
    //    }

    CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata);
    //    {
    //        MessageAcknowledgementConfiguration.Outcome outcome;
    //        if (metadata != null) {
    //            outcome = metadata.get(SettleMetadata.class)
    //                    .map(SettleMetadata::getOutcome)
    //                    .orElseGet(() -> messageOutCome /* TODO get outcome from reason */);
    //        } else {
    //            outcome = messageOutCome != null ? messageOutCome
    //                    : MessageAcknowledgementConfiguration.Outcome.FAILED;
    //        }
    //
    //        SolaceLogging.log.messageSettled(channel, outcome.toString().toLowerCase(), reason.getMessage());
    //        return Uni.createFrom().voidItem()
    //                .invoke(() -> ackSupport.settle(msg.getMessage(), outcome))
    //                .runSubscriptionOn(msg::runOnMessageContext)
    //                .subscribeAsCompletionStage();
    //    }
}
