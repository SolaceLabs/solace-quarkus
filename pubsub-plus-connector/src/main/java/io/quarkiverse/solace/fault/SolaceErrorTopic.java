package io.quarkiverse.solace.fault;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.receiver.AcknowledgementSupport;

import io.quarkiverse.solace.i18n.SolaceLogging;
import io.quarkiverse.solace.incoming.SolaceInboundMessage;

public class SolaceErrorTopic implements SolaceFailureHandler {
    private final String channel;
    private final AcknowledgementSupport ackSupport;
    private final MessagingService solace;

    private final SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler;
    private long maxDeliveryAttempts;
    private String errorTopic;
    private boolean dmqEligible;
    private Long timeToLive;

    public SolaceErrorTopic(String channel, AcknowledgementSupport ackSupport, MessagingService solace) {
        this.channel = channel;
        this.ackSupport = ackSupport;
        this.solace = solace;
        this.solaceErrorTopicPublisherHandler = new SolaceErrorTopicPublisherHandler(solace);
    }

    public void setMaxDeliveryAttempts(long maxDeliveryAttempts) {
        this.maxDeliveryAttempts = maxDeliveryAttempts;
    }

    public void setErrorTopic(String errorTopic) {
        this.errorTopic = errorTopic;
    }

    public void setDmqEligible(boolean dmqEligible) {
        this.dmqEligible = dmqEligible;
    }

    public void setTimeToLive(Long timeToLive) {
        this.timeToLive = timeToLive;
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
                    ackSupport.settle(msg.getMessage(), MessageAcknowledgementConfiguration.Outcome.ACCEPTED);
                })
                .replaceWithVoid()
                .onFailure().invoke(t -> SolaceLogging.log.unsuccessfulToTopic(errorTopic, channel, t.getMessage()))
                .emitOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}
