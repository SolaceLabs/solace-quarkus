package io.quarkiverse.solace.outgoing;

import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PersistentMessagePublisherBuilder;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.publisher.PersistentMessagePublisher.PublishReceipt;
import com.solace.messaging.publisher.PublisherHealthCheck;
import com.solace.messaging.resources.Topic;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.quarkiverse.solace.SolaceConnectorOutgoingConfiguration;
import io.quarkiverse.solace.i18n.SolaceLogging;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.smallrye.reactive.messaging.OutgoingMessageMetadata;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.providers.helpers.MultiUtils;
import io.vertx.core.json.Json;
import io.vertx.mutiny.core.Vertx;

public class SolaceOutgoingChannel
        implements PersistentMessagePublisher.MessagePublishReceiptListener, PublisherHealthCheck.PublisherReadinessListener {

    private final PersistentMessagePublisher publisher;
    private final String channel;
    private final Flow.Subscriber<? extends Message<?>> subscriber;
    private final Topic topic;
    private final SenderProcessor processor;
    private boolean isPublisherReady = true;

    private long waitTimeout = -1;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final UnsignedCounterBarrier publishedMessagesTracker = new UnsignedCounterBarrier();

    public SolaceOutgoingChannel(Vertx vertx, SolaceConnectorOutgoingConfiguration oc, MessagingService solace) {
        this.channel = oc.getChannel();
        PersistentMessagePublisherBuilder builder = solace.createPersistentMessagePublisherBuilder();
        switch (oc.getProducerBackPressureStrategy()) {
            case "elastic":
                builder.onBackPressureElastic();
                break;
            case "wait":
                builder.onBackPressureWait(oc.getProducerBackPressureBufferCapacity());
                break;
            default:
                builder.onBackPressureReject(oc.getProducerBackPressureBufferCapacity());
                break;
        }
        this.waitTimeout = oc.getClientShutdownWaitTimeout();
        oc.getProducerDeliveryAckTimeout().ifPresent(builder::withDeliveryAckTimeout);
        oc.getProducerDeliveryAckWindowSize().ifPresent(builder::withDeliveryAckWindowSize);
        this.publisher = builder.build();
        if (oc.getProducerWaitForPublishReceipt()) {
            publisher.setMessagePublishReceiptListener(this);
        }
        boolean lazyStart = oc.getClientLazyStart();
        this.topic = Topic.of(oc.getProducerTopic().orElse(this.channel));
        this.processor = new SenderProcessor(oc.getProducerMaxInflightMessages(), oc.getProducerWaitForPublishReceipt(),
                m -> sendMessage(solace, m, oc.getProducerWaitForPublishReceipt()));
        this.subscriber = MultiUtils.via(processor, multi -> multi.plug(
                m -> lazyStart ? m.onSubscription().call(() -> Uni.createFrom().completionStage(publisher.startAsync())) : m));
        if (!lazyStart) {
            this.publisher.start();
        }

        this.publisher.setPublisherReadinessListener(new PublisherHealthCheck.PublisherReadinessListener() {
            @Override
            public void ready() {
                isPublisherReady = true;
            }
        });
    }

    private Uni<Void> sendMessage(MessagingService solace, Message<?> m, boolean waitForPublishReceipt) {

        // TODO - Use isPublisherReady to check if publisher is in ready state before publishing. This is required when back-pressure is set to reject. We need to block this call till isPublisherReady is true
        return publishMessage(publisher, m, solace.messageBuilder(), waitForPublishReceipt)
                .onItem().transformToUni(receipt -> {
                    if (receipt != null) {
                        OutgoingMessageMetadata.setResultOnMessage(m, receipt);
                    }
                    return Uni.createFrom().completionStage(m.getAck());
                })
                .onFailure().recoverWithUni(t -> Uni.createFrom().completionStage(m.nack(t)));

    }

    private Uni<PublishReceipt> publishMessage(PersistentMessagePublisher publisher, Message<?> m,
            OutboundMessageBuilder msgBuilder, boolean waitForPublishReceipt) {
        publishedMessagesTracker.increment();
        AtomicReference<Topic> topic = new AtomicReference<>(this.topic);
        OutboundMessage outboundMessage;
        m.getMetadata(SolaceOutboundMetadata.class).ifPresent(metadata -> {
            if (metadata.getHttpContentHeaders() != null && !metadata.getHttpContentHeaders().isEmpty()) {
                metadata.getHttpContentHeaders().forEach(msgBuilder::withHTTPContentHeader);
            }
            if (metadata.getProperties() != null && !metadata.getProperties().isEmpty()) {
                metadata.getProperties().forEach(msgBuilder::withProperty);
            }
            if (metadata.getExpiration() != null) {
                msgBuilder.withExpiration(metadata.getExpiration());
            }
            if (metadata.getPriority() != null) {
                msgBuilder.withPriority(metadata.getPriority());
            }
            if (metadata.getSenderId() != null) {
                msgBuilder.withSenderId(metadata.getSenderId());
            }
            if (metadata.getApplicationMessageType() != null) {
                msgBuilder.withApplicationMessageType(metadata.getApplicationMessageType());
            }
            if (metadata.getTimeToLive() != null) {
                msgBuilder.withTimeToLive(metadata.getTimeToLive());
            }
            if (metadata.getApplicationMessageId() != null) {
                msgBuilder.withApplicationMessageId(metadata.getApplicationMessageId());
            }
            if (metadata.getClassOfService() != null) {
                msgBuilder.withClassOfService(metadata.getClassOfService());
            }

            if (metadata.getDynamicDestination() != null) {
                topic.set(Topic.of(metadata.getDynamicDestination()));
            }
        });
        Object payload = m.getPayload();
        if (payload instanceof OutboundMessage) {
            outboundMessage = (OutboundMessage) payload;
        } else if (payload instanceof String) {
            outboundMessage = msgBuilder
                    .withHTTPContentHeader(HttpHeaderValues.TEXT_PLAIN.toString(), "")
                    .build((String) payload);
        } else if (payload instanceof byte[]) {
            outboundMessage = msgBuilder.build((byte[]) payload);
        } else {
            outboundMessage = msgBuilder
                    .withHTTPContentHeader(HttpHeaderValues.APPLICATION_JSON.toString(), "")
                    .build(Json.encode(payload));
        }
        return Uni.createFrom().<PublishReceipt> emitter(e -> {
            boolean exitExceptionally = false;
            try {
                if(isPublisherReady) {
                    if (waitForPublishReceipt) {
                        publisher.publish(outboundMessage, topic.get(), e);
                    } else {
                        publisher.publish(outboundMessage, topic.get());
                        e.complete(null);
                        publishedMessagesTracker.decrement();
                    }
                }
            } catch (PubSubPlusClientException.PublisherOverflowException publisherOverflowException) {
                isPublisherReady = false;
                exitExceptionally = true;
                e.fail(publisherOverflowException);
            } catch (Throwable t) {
                e.fail(t);
            } finally {
                if (exitExceptionally) {
                    publisher.notifyWhenReady();
                }
            }
        }).invoke(() -> SolaceLogging.log.successfullyToTopic(channel, topic.get().getName()));
    }

    public Flow.Subscriber<? extends Message<?>> getSubscriber() {
        return this.subscriber;
    }

    public void waitForPublishedMessages() {
        try {
            if (!publishedMessagesTracker.awaitEmpty(this.waitTimeout, TimeUnit.MILLISECONDS)) {
                SolaceLogging.log.info(String.format("Timed out while waiting for the" +
                        " remaining messages to get publish acknowledgment."));
            }
        } catch (InterruptedException e) {
            SolaceLogging.log.info(String.format("Interrupted while waiting for messages to get acknowledged"));
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (processor != null) {
            processor.cancel();
        }

        publisher.terminate(5000);
    }

    @Override
    public void onPublishReceipt(PublishReceipt publishReceipt) {
        UniEmitter<PublishReceipt> uniEmitter = (UniEmitter<PublishReceipt>) publishReceipt.getUserContext();
        PubSubPlusClientException exception = publishReceipt.getException();
        if (exception != null) {
            uniEmitter.fail(exception);
        } else {
            publishedMessagesTracker.decrement();
            uniEmitter.complete(publishReceipt);
        }
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {

    }

    public void isReady(HealthReport.HealthReportBuilder builder) {

    }

    public void isAlive(HealthReport.HealthReportBuilder builder) {

    }

    @Override
    public void ready() {
        isPublisherReady = true;
    }
}
