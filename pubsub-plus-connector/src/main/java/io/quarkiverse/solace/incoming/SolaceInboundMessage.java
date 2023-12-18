package io.quarkiverse.solace.incoming;

import static io.smallrye.reactive.messaging.providers.locals.ContextAwareMessage.captureContextMetadata;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import io.smallrye.mutiny.unchecked.Unchecked;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.config.MessageAcknowledgementConfiguration;
import com.solace.messaging.publisher.PersistentMessagePublisher.PublishReceipt;
import com.solace.messaging.receiver.InboundMessage;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.quarkiverse.solace.SolaceConnectorIncomingConfiguration;
import io.quarkiverse.solace.i18n.SolaceLogging;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.providers.MetadataInjectableMessage;
import io.smallrye.reactive.messaging.providers.locals.ContextAwareMessage;
import io.vertx.core.buffer.Buffer;

public class SolaceInboundMessage<T> implements ContextAwareMessage<T>, MetadataInjectableMessage<T> {

    private final InboundMessage msg;
    private final SolaceAckHandler ackHandler;
    private final SolaceFailureHandler nackHandler;
    private final SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler;
    private final SolaceConnectorIncomingConfiguration ic;
    private final T payload;
    private final UnsignedCounterBarrier unacknowledgedMessageTracker;

    private Metadata metadata;

    public SolaceInboundMessage(InboundMessage message, SolaceAckHandler ackHandler, SolaceFailureHandler nackHandler,
            SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler,
            SolaceConnectorIncomingConfiguration ic, UnsignedCounterBarrier unacknowledgedMessageTracker) {
        this.msg = message;
        this.unacknowledgedMessageTracker = unacknowledgedMessageTracker;
        this.payload = (T) convertPayload();
        this.ackHandler = ackHandler;
        this.nackHandler = nackHandler;
        this.solaceErrorTopicPublisherHandler = solaceErrorTopicPublisherHandler;
        this.ic = ic;
        this.metadata = captureContextMetadata(new SolaceInboundMetadata(message));
    }

    public InboundMessage getMessage() {
        return msg;
    }

    @Override
    public T getPayload() {
        return this.payload;
    }

    private Object convertPayload() {
        // Neither of these are guaranteed to be non-null
        final String contentType = msg.getRestInteroperabilitySupport().getHTTPContentType();
        final String contentEncoding = msg.getRestInteroperabilitySupport().getHTTPContentEncoding();
        final Buffer body = Buffer.buffer(msg.getPayloadAsBytes());

        // If there is a content encoding specified, we don't try to unwrap
        if (contentEncoding == null || contentEncoding.isBlank()) {
            try {
                // Do our best with text and json
                if (HttpHeaderValues.APPLICATION_JSON.toString().equalsIgnoreCase(contentType)) {
                    // This could be  JsonArray, JsonObject, String etc. depending on buffer contents
                    return body.toJson();
                } else if (HttpHeaderValues.TEXT_PLAIN.toString().equalsIgnoreCase(contentType)) {
                    return body.toString();
                }
            } catch (Throwable t) {
                SolaceLogging.log.typeConversionFallback();
            }
            // Otherwise fall back to raw byte array
        } else {
            // Just silence the warning if we have a binary message
            if (!HttpHeaderValues.APPLICATION_OCTET_STREAM.toString().equalsIgnoreCase(contentType)) {
                SolaceLogging.log.typeConversionFallback();
            }
        }

        this.unacknowledgedMessageTracker.increment();
        return body.getBytes();
    }

    @Override
    public Metadata getMetadata() {
        return metadata;
    }

    @Override
    public CompletionStage<Void> ack() {
        this.unacknowledgedMessageTracker.decrement();
        return ackHandler.handle(this);
    }

    @Override
    public CompletionStage<Void> nack(Throwable reason, Metadata nackMetadata) {
        if (solaceErrorTopicPublisherHandler != null) {
            PublishReceipt publishReceipt = solaceErrorTopicPublisherHandler.handle(this, ic)
                    .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(ic.getConsumerQueueErrorMessageMaxDeliveryAttempts())
                    .onFailure().transform((throwable -> {
                        SolaceLogging.log.unsuccessfulToTopic(ic.getConsumerQueueErrorTopic().get(), ic.getChannel());
                        throw new RuntimeException(throwable);
                    }))
                    .await().atMost(Duration.ofSeconds(30));

            if (publishReceipt != null) {
                this.unacknowledgedMessageTracker.decrement();
                return nackHandler.handle(this, reason, nackMetadata, MessageAcknowledgementConfiguration.Outcome.ACCEPTED);
            }
        }

        MessageAcknowledgementConfiguration.Outcome outcome = ic.getConsumerQueueEnableNacks()
                && ic.getConsumerQueueDiscardMessagesOnFailure() && solaceErrorTopicPublisherHandler == null
                        ? MessageAcknowledgementConfiguration.Outcome.REJECTED
                        : MessageAcknowledgementConfiguration.Outcome.FAILED;
        if (outcome == MessageAcknowledgementConfiguration.Outcome.REJECTED) {
            this.unacknowledgedMessageTracker.decrement();
        }
        return ic.getConsumerQueueEnableNacks()
                ? nackHandler.handle(this, reason, nackMetadata, outcome)
                : Uni.createFrom().voidItem().subscribeAsCompletionStage();
    }

    @Override
    public void injectMetadata(Object metadataObject) {
        this.metadata = this.metadata.with(metadataObject);
    }
}
