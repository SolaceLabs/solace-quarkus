package com.solace.quarkus.messaging.fault;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.receiver.AcknowledgementSupport;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

public class SolaceErrorTopic implements SolaceFailureHandler {
    private final String channel;
    // Supplier so the handler always targets the current receiver, which is
    // rebuilt on reconnect (DATAGO-141425).
    private final Supplier<AcknowledgementSupport> ackSupport;

    private final SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler;
    private final long maxDeliveryAttempts;
    private final String errorTopic;
    private final boolean dmqEligible;
    private final Long timeToLive;

    public SolaceErrorTopic(String channel, String errorTopic, boolean dmqEligible, Long timeToLive, long maxDeliveryAttempts,
            Supplier<AcknowledgementSupport> ackSupport, MessagingService solace) {
        this.channel = channel;
        this.errorTopic = errorTopic;
        this.dmqEligible = dmqEligible;
        this.timeToLive = timeToLive;
        this.maxDeliveryAttempts = maxDeliveryAttempts;
        this.ackSupport = ackSupport;
        this.solaceErrorTopicPublisherHandler = new SolaceErrorTopicPublisherHandler(solace);
    }

    @Override
    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata) {
        return solaceErrorTopicPublisherHandler.handle(msg, errorTopic, dmqEligible, timeToLive)
                .onFailure().retry().withBackOff(Duration.ofSeconds(1))
                .atMost(maxDeliveryAttempts)
                .onItem().invoke(() -> {
                    SolaceLogging.log.messageSettled(channel,
                            MessageAcknowledgementConfiguration.Outcome.ACCEPTED.toString().toLowerCase(),
                            "Message is published to error topic and acknowledged on queue.");
                    AcknowledgementSupport support = ackSupport.get();
                    if (support != null) {
                        support.settle(msg.getMessage(), MessageAcknowledgementConfiguration.Outcome.ACCEPTED);
                    }
                })
                .replaceWithVoid()
                .onFailure().invoke(t -> SolaceLogging.log.unsuccessfulToTopic(errorTopic, channel, t))
                .emitOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}