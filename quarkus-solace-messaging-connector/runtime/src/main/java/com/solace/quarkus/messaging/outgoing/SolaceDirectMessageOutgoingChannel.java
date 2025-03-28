package com.solace.quarkus.messaging.outgoing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.DirectMessagePublisherBuilder;
import com.solace.messaging.MessagingService;
import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.config.SolaceConstants;
import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.publisher.*;
import com.solace.messaging.resources.Topic;
import com.solace.quarkus.messaging.SolaceConnectorOutgoingConfiguration;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.tracing.SolaceOpenTelemetryInstrumenter;
import com.solace.quarkus.messaging.tracing.SolaceTrace;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.providers.helpers.MultiUtils;
import io.vertx.core.json.Json;
import io.vertx.mutiny.core.Vertx;

public class SolaceDirectMessageOutgoingChannel
        implements PublisherHealthCheck.PublisherReadinessListener {

    private final DirectMessagePublisher publisher;
    private final String channel;
    private final Flow.Subscriber<? extends Message<?>> subscriber;
    private final Topic topic;
    private final SenderProcessor processor;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final List<Throwable> failures = new ArrayList<>();
    private final SolaceOpenTelemetryInstrumenter solaceOpenTelemetryInstrumenter;
    private final MessagingService solace;
    private volatile boolean isPublisherReady = true;
    // Assuming we won't ever exceed the limit of an unsigned long...
    private final OutgoingMessagesUnsignedCounterBarrier publishedMessagesTracker = new OutgoingMessagesUnsignedCounterBarrier();

    public SolaceDirectMessageOutgoingChannel(Vertx vertx, Instance<OpenTelemetry> openTelemetryInstance,
            SolaceConnectorOutgoingConfiguration oc, MessagingService solace) {
        this.solace = solace;
        this.channel = oc.getChannel();
        DirectMessagePublisherBuilder builder = solace.createDirectMessagePublisherBuilder();
        switch (oc.getProducerBackPressureStrategy()) {
            case "wait":
                builder.onBackPressureWait(oc.getProducerBackPressureBufferCapacity());
                break;
            case "reject":
                builder.onBackPressureReject(oc.getProducerBackPressureBufferCapacity());
                break;
            default:
                builder.onBackPressureElastic();
                break;
        }
        this.gracefulShutdown = oc.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = oc.getClientGracefulShutdownWaitTimeout();
        this.publisher = builder.build();
        boolean lazyStart = oc.getClientLazyStart();
        this.topic = Topic.of(oc.getProducerTopic().orElse(this.channel));
        if (oc.getClientTracingEnabled()) {
            solaceOpenTelemetryInstrumenter = SolaceOpenTelemetryInstrumenter.createForOutgoing(openTelemetryInstance);
        } else {
            solaceOpenTelemetryInstrumenter = null;
        }
        this.processor = new SenderProcessor(oc.getProducerMaxInflightMessages(), oc.getProducerWaitForPublishReceipt(),
                m -> sendMessage(solace, m, oc.getClientTracingEnabled()).onFailure()
                        .invoke(this::reportFailure));
        this.subscriber = MultiUtils.via(processor, multi -> multi.plug(
                m -> lazyStart ? m.onSubscription().call(() -> Uni.createFrom().completionStage(publisher.startAsync())) : m));
        if (!lazyStart) {
            this.publisher.start();
        }

        this.publisher.setPublisherReadinessListener(() -> isPublisherReady = true);
        this.publisher.setPublishFailureListener(failedPublishEvent -> {
            SolaceLogging.log.error("Failed to publish direct message");
            reportFailure(failedPublishEvent.getException());
        });
    }

    private Uni<Void> sendMessage(MessagingService solace, Message<?> m, boolean isTracingEnabled) {

        // TODO - Use isPublisherReady to check if publisher is in ready state before publishing. This is required when back-pressure is set to reject. We need to block this call till isPublisherReady is true
        return publishMessage(publisher, m, solace.messageBuilder(), isTracingEnabled)
                .onItem().transformToUni(receipt -> {
                    alive.set(true);
                    return Uni.createFrom().completionStage(m.getAck());
                })
                .onFailure().recoverWithUni(t -> {
                    reportFailure(t);
                    return Uni.createFrom().completionStage(m.nack(t));
                });
    }

    private synchronized void reportFailure(Throwable throwable) {
        alive.set(false);
        // Don't keep all the failures, there are only there for reporting.
        if (failures.size() == 10) {
            failures.remove(0);
        }
        failures.add(throwable);
    }

    private Uni<Object> publishMessage(DirectMessagePublisher publisher, Message<?> m,
            OutboundMessageBuilder msgBuilder, boolean isTracingEnabled) {
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
            if (metadata.getPartitionKey() != null) {
                msgBuilder.withProperty(SolaceConstants.MessageUserPropertyConstants.QUEUE_PARTITION_KEY,
                        metadata.getPartitionKey());
            }
            if (metadata.getCorrelationId() != null) {
                msgBuilder.withProperty(SolaceProperties.MessageProperties.CORRELATION_ID, metadata.getCorrelationId());
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

        if (isTracingEnabled) {
            SolaceTrace solaceTrace = new SolaceTrace.Builder()
                    .withDestinationKind("topic")
                    .withTopic(topic.get().getName())
                    .withMessageID(outboundMessage.getApplicationMessageId())
                    .withCorrelationID(outboundMessage.getCorrelationId())
                    .withPartitionKey(
                            outboundMessage
                                    .hasProperty(SolaceConstants.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                            ? outboundMessage
                                                    .getProperty(
                                                            SolaceConstants.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                            : null)
                    .withPayloadSize(Long.valueOf(outboundMessage.getPayloadAsBytes().length))
                    .withProperties(outboundMessage.getProperties()).build();
            solaceOpenTelemetryInstrumenter.traceOutgoing(m, solaceTrace);
        }

        return Uni.createFrom().<Object> emitter(e -> {
            boolean exitExceptionally = false;
            try {
                if (isPublisherReady) {
                    publisher.publish(outboundMessage, topic.get());
                    publishedMessagesTracker.decrement();
                    e.complete(null);
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
            SolaceLogging.log.infof("Waiting for outgoing channel %s messages to be published", channel);
            if (!publishedMessagesTracker.awaitEmpty(this.gracefulShutdownWaitTimeout, TimeUnit.MILLISECONDS)) {
                SolaceLogging.log.infof("Timed out while waiting for the" +
                        " remaining messages to be acknowledged on channel %s.", channel);
            }
        } catch (InterruptedException e) {
            SolaceLogging.log.infof("Interrupted while waiting for messages on channel %s to get acknowledged", channel);
            throw new RuntimeException(e);
        }
    }

    public void close() {
        if (this.gracefulShutdown) {
            waitForPublishedMessages();
        }
        if (processor != null) {
            processor.cancel();
        }

        publisher.terminate(5000);
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected());
    }

    public void isReady(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected() && this.publisher != null && this.publisher.isReady());
    }

    public void isAlive(HealthReport.HealthReportBuilder builder) {
        List<Throwable> reportedFailures;
        if (!failures.isEmpty()) {
            synchronized (this) {
                reportedFailures = new ArrayList<>(failures);
            }
            builder.add(channel, solace.isConnected() && alive.get(),
                    reportedFailures.stream().map(Throwable::getMessage).collect(Collectors.joining()));
        } else {
            builder.add(channel, solace.isConnected() && alive.get());
        }
    }

    @Override
    public void ready() {
        isPublisherReady = true;
    }
}
