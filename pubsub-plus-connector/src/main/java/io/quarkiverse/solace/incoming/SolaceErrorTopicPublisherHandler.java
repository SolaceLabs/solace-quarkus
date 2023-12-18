package io.quarkiverse.solace.incoming;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.publisher.PersistentMessagePublisher.PublishReceipt;
import com.solace.messaging.resources.Topic;

import io.quarkiverse.solace.SolaceConnectorIncomingConfiguration;
import io.quarkiverse.solace.i18n.SolaceLogging;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;

class SolaceErrorTopicPublisherHandler implements PersistentMessagePublisher.MessagePublishReceiptListener {

    private final MessagingService solace;
    private final String errorTopic;
    private final PersistentMessagePublisher publisher;
    private final OutboundErrorMessageMapper outboundErrorMessageMapper;

    public SolaceErrorTopicPublisherHandler(MessagingService solace, String errorTopic) {
        this.solace = solace;
        this.errorTopic = errorTopic;

        publisher = solace.createPersistentMessagePublisherBuilder().build();
        publisher.start();
        outboundErrorMessageMapper = new OutboundErrorMessageMapper();
    }

    public Uni<PublishReceipt> handle(SolaceInboundMessage<?> message,
            SolaceConnectorIncomingConfiguration ic) {
        OutboundMessage outboundMessage = outboundErrorMessageMapper.mapError(this.solace.messageBuilder(),
                message.getMessage(),
                ic);
        publisher.setMessagePublishReceiptListener(this);
        //        }
        return Uni.createFrom().<PublishReceipt>emitter(e -> {
            try {
                // always wait for error message publish receipt to ensure it is successfully spooled on broker.
                publisher.publish(outboundMessage, Topic.of(errorTopic), e);
            } catch (Throwable t) {
                SolaceLogging.log.publishException(this.errorTopic);
                e.fail(t);
            }
        }).invoke(() -> System.out.println(""));
    }

    @Override
    public void onPublishReceipt(PublishReceipt publishReceipt) {
        UniEmitter<PublishReceipt> uniEmitter = (UniEmitter<PublishReceipt>) publishReceipt
                .getUserContext();
        PubSubPlusClientException exception = publishReceipt.getException();
        if (exception != null) {
            SolaceLogging.log.publishException(this.errorTopic);
            uniEmitter.fail(exception);
        } else {
            uniEmitter.complete(publishReceipt);
        }
    }
}
