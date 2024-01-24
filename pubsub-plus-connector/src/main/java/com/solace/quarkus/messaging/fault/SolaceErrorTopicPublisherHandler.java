package com.solace.quarkus.messaging.fault;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.publisher.PersistentMessagePublisher.PublishReceipt;
import com.solace.messaging.resources.Topic;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;

class SolaceErrorTopicPublisherHandler implements PersistentMessagePublisher.MessagePublishReceiptListener {

    private final MessagingService solace;
    private final PersistentMessagePublisher publisher;
    private final OutboundErrorMessageMapper outboundErrorMessageMapper;

    public SolaceErrorTopicPublisherHandler(MessagingService solace) {
        this.solace = solace;

        publisher = solace.createPersistentMessagePublisherBuilder().build();
        publisher.setMessagePublishReceiptListener(this);
        publisher.start();
        outboundErrorMessageMapper = new OutboundErrorMessageMapper();
    }

    public Uni<PublishReceipt> handle(SolaceInboundMessage<?> message,
            String errorTopic,
            boolean dmqEligible, Long timeToLive) {
        OutboundMessage outboundMessage = outboundErrorMessageMapper.mapError(this.solace.messageBuilder(),
                message.getMessage(),
                dmqEligible, timeToLive);
        //        }
        return Uni.createFrom().<PublishReceipt> emitter(e -> {
            try {
                // always wait for error message publish receipt to ensure it is successfully spooled on broker.
                publisher.publish(outboundMessage, Topic.of(errorTopic), e);
            } catch (Throwable t) {
                e.fail(t);
            }
        }).onFailure().invoke(t -> SolaceLogging.log.publishException(errorTopic, t));
    }

    @Override
    public void onPublishReceipt(PublishReceipt publishReceipt) {
        UniEmitter<PublishReceipt> uniEmitter = (UniEmitter<PublishReceipt>) publishReceipt
                .getUserContext();
        PubSubPlusClientException exception = publishReceipt.getException();
        if (exception != null) {
            uniEmitter.fail(exception);
        } else {
            uniEmitter.complete(publishReceipt);
        }
    }
}
