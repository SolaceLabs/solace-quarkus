package com.solace.quarkus.messaging.incoming;

import static io.smallrye.reactive.messaging.providers.locals.ContextAwareMessage.captureContextMetadata;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.messaging.receiver.InboundMessage;
import com.solace.quarkus.messaging.fault.SolaceFailureHandler;
import com.solace.quarkus.messaging.i18n.SolaceLogging;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.providers.MetadataInjectableMessage;
import io.smallrye.reactive.messaging.providers.locals.ContextAwareMessage;
import io.vertx.core.buffer.Buffer;

public class SolaceInboundMessage<T> implements ContextAwareMessage<T>, MetadataInjectableMessage<T> {

    private final InboundMessage msg;
    private final SolaceAckHandler ackHandler;
    private final SolaceFailureHandler nackHandler;
    private final T payload;
    private final IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker;
    private final Consumer<Throwable> reportFailure;
    private Metadata metadata;

    public SolaceInboundMessage(InboundMessage message, SolaceAckHandler ackHandler, SolaceFailureHandler nackHandler,
            IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker, Consumer<Throwable> reportFailure) {
        this.msg = message;
        this.unacknowledgedMessageTracker = unacknowledgedMessageTracker;
        this.payload = (T) convertPayload();
        this.ackHandler = ackHandler;
        this.nackHandler = nackHandler;
        this.metadata = captureContextMetadata(new SolaceInboundMetadata(message));
        this.reportFailure = reportFailure;
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
    public Supplier<CompletionStage<Void>> getAck() {
        return this::ack;
    }

    @Override
    public Function<Throwable, CompletionStage<Void>> getNack() {
        return this::nack;
    }

    @Override
    public CompletionStage<Void> ack() {
        this.unacknowledgedMessageTracker.decrement();
        if (this.ackHandler != null) {
            return ackHandler.handle(this);
        }

        return Uni.createFrom().voidItem().subscribeAsCompletionStage();
    }

    @Override
    public CompletionStage<Void> nack(Throwable reason, Metadata nackMetadata) {
        this.unacknowledgedMessageTracker.decrement();
        this.reportFailure.accept(reason);
        return nackHandler.handle(this, reason, nackMetadata);

        //        if (solaceErrorTopicPublisherHandler == null) {
        //            // REJECTED - Will move message to DMQ if enabled, FAILED - Will redeliver the message.
        //            MessageAcknowledgementConfiguration.Outcome outcome = ic.getConsumerQueueEnableNacks()
        //                    ? (ic.getConsumerQueueDiscardMessagesOnFailure() ? MessageAcknowledgementConfiguration.Outcome.REJECTED
        //                            : MessageAcknowledgementConfiguration.Outcome.FAILED)
        //                    : null; // if nacks are not supported on broker, no outcome is required.
        //            if (outcome != null) {
        //                // decrement the tracker, as the message might get redelivered or moved to DMQ
        //                this.unacknowledgedMessageTracker.decrement();
        //                return nackHandler.handle(this, reason, nackMetadata, outcome);
        //            }
        //        } else {
        //            PublishReceipt publishReceipt = solaceErrorTopicPublisherHandler.handle(this, ic)
        //                    .onFailure().retry().withBackOff(Duration.ofSeconds(1))
        //                    .atMost(ic.getConsumerQueueErrorMessageMaxDeliveryAttempts())
        //                    .subscribeAsCompletionStage().exceptionally((t) -> {
        //                        SolaceLogging.log.unsuccessfulToTopic(ic.getConsumerQueueErrorTopic().get(), ic.getChannel(),
        //                                t.getMessage());
        //                        return null;
        //                    }).join();
        //
        //            if (publishReceipt != null) {
        //                // decrement the tracker, as the message might get redelivered or moved to DMQ
        //                this.unacknowledgedMessageTracker.decrement();
        //                return nackHandler.handle(this, reason, nackMetadata, MessageAcknowledgementConfiguration.Outcome.ACCEPTED);
        //            } else {
        //                if (ic.getConsumerQueueEnableNacks()) {
        //                    // decrement the tracker, as the message might get redelivered or moved to DMQ
        //                    this.unacknowledgedMessageTracker.decrement();
        //                    return nackHandler.handle(this, reason, nackMetadata,
        //                            MessageAcknowledgementConfiguration.Outcome.FAILED);
        //                }
        //            }
        //        }
        //
        //        // decrement the tracker, as the message might get redelivered or moved to DMQ
        //        this.unacknowledgedMessageTracker.decrement();
        //        // return void stage if above check fail. This will not nack the message on broker.
        //        return Uni.createFrom().voidItem().subscribeAsCompletionStage(); // TODO - Restart receiver to redeliver message, needed when nacks are not supported on broker.
    }

    @Override
    public void injectMetadata(Object metadataObject) {
        this.metadata = this.metadata.with(metadataObject);
    }
}
