package io.quarkiverse.solace.fault;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.AcknowledgementSupport;

import io.quarkiverse.solace.i18n.SolaceLogging;
import io.quarkiverse.solace.incoming.SolaceInboundMessage;
import io.smallrye.mutiny.Uni;

public class SolaceErrorTopic implements SolaceFailureHandler {
    private final String channel;
    private final AcknowledgementSupport ackSupport;
    private final MessagingService solace;

    private final SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler;
    private long maxDeliveryAttempts;
    private String errorTopic;
    private boolean dmqEligible;
    private Long timeToLive;
    private boolean supportsNacks;

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

    public void setSupportsNacks(boolean supportsNacks) {
        this.supportsNacks = supportsNacks;
    }

    @Override
    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata) {
        PersistentMessagePublisher.PublishReceipt publishReceipt = solaceErrorTopicPublisherHandler
                .handle(msg, errorTopic, dmqEligible, timeToLive)
                .onFailure().retry().withBackOff(Duration.ofSeconds(1))
                .atMost(maxDeliveryAttempts)
                .subscribeAsCompletionStage().exceptionally((t) -> {
                    SolaceLogging.log.unsuccessfulToTopic(errorTopic, channel,
                            t.getMessage());
                    return null;
                }).join();

        if (publishReceipt != null) {
            return Uni.createFrom().voidItem()
                    .invoke(() -> ackSupport.settle(msg.getMessage(), MessageAcknowledgementConfiguration.Outcome.ACCEPTED))
                    .runSubscriptionOn(msg::runOnMessageContext)
                    .subscribeAsCompletionStage();
        } else {
            if (supportsNacks) {
                return Uni.createFrom().voidItem()
                        .invoke(() -> ackSupport.settle(msg.getMessage(), MessageAcknowledgementConfiguration.Outcome.FAILED))
                        .runSubscriptionOn(msg::runOnMessageContext)
                        .subscribeAsCompletionStage();
            }
        }

        return Uni.createFrom().voidItem().subscribeAsCompletionStage(); // TODO :: Restart receiver to redeliver message - needed when nacks are not supported.
    }
}
