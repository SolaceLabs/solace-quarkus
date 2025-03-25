package com.solace.quarkus.messaging;

import static io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;

import com.solace.messaging.MessagingService;
import com.solace.quarkus.messaging.incoming.SolaceDirectMessageIncomingChannel;
import com.solace.quarkus.messaging.incoming.SolaceIncomingChannel;
import com.solace.quarkus.messaging.outgoing.SolaceDirectMessageOutgoingChannel;
import com.solace.quarkus.messaging.outgoing.SolaceOutgoingChannel;

import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.reactive.messaging.annotations.ConnectorAttribute;
import io.smallrye.reactive.messaging.connector.InboundConnector;
import io.smallrye.reactive.messaging.connector.OutboundConnector;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.health.HealthReporter;
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder;
import io.vertx.mutiny.core.Vertx;

@ApplicationScoped
@Connector(SolaceConnector.CONNECTOR_NAME)

// TODO only persisted is implemented
@ConnectorAttribute(name = "client.type", type = "string", direction = INCOMING_AND_OUTGOING, description = "Direct or persisted", defaultValue = "persistent")
@ConnectorAttribute(name = "client.lazy.start", type = "boolean", direction = INCOMING_AND_OUTGOING, description = "Whether the receiver or publisher is started at initialization or lazily at subscription time", defaultValue = "false")
@ConnectorAttribute(name = "client.graceful-shutdown", type = "boolean", direction = INCOMING_AND_OUTGOING, description = "Whether to shutdown client gracefully", defaultValue = "true")
@ConnectorAttribute(name = "client.tracing-enabled", type = "boolean", direction = INCOMING_AND_OUTGOING, description = "Whether to enable tracing for incoming and outgoing messages", defaultValue = "false")
@ConnectorAttribute(name = "client.graceful-shutdown.wait-timeout", type = "long", direction = INCOMING_AND_OUTGOING, description = "Timeout in milliseconds to wait for messages to finish processing before shutdown", defaultValue = "10000")

@ConnectorAttribute(name = "client.type.direct.back-pressure.strategy", type = "string", direction = INCOMING, description = "It is possible for the client application to consume messages more quickly than the API can send them to the broker due to network congestion or connectivity issues. This delay can cause the internal buffer to accumulate messages until it reaches its capacity, preventing the API from storing any more messages.", defaultValue = "elastic")
@ConnectorAttribute(name = "client.type.direct.back-pressure.buffer-capacity", type = "int", direction = INCOMING, description = "It is possible for the client application to consume messages more quickly than the API can send them to the broker due to network congestion or connectivity issues. This delay can cause the internal buffer to accumulate messages until it reaches its capacity, preventing the API from storing any more messages.", defaultValue = "1024")
@ConnectorAttribute(name = "consumer.queue.name", type = "string", direction = INCOMING, description = "The queue name of receiver.")
@ConnectorAttribute(name = "consumer.queue.type", type = "string", direction = INCOMING, description = "The queue type of receiver. Supported values `durable-exclusive`, `durable-non-exclusive`, `non-durable-exclusive`", defaultValue = "durable-exclusive")
@ConnectorAttribute(name = "consumer.queue.missing-resource-creation-strategy", type = "string", direction = INCOMING, description = "Missing resource creation strategy", defaultValue = "do-not-create")
@ConnectorAttribute(name = "consumer.queue.add-additional-subscriptions", type = "boolean", direction = INCOMING, description = "Whether to add configured subscriptions to queue. Will fail if permissions to configure subscriptions is not allowed on broker", defaultValue = "false")
@ConnectorAttribute(name = "consumer.subscriptions", type = "string", direction = INCOMING, description = "The comma separated list of subscriptions, the channel name if empty")
@ConnectorAttribute(name = "consumer.queue.selector-query", type = "string", direction = INCOMING, description = "The receiver selector query")
@ConnectorAttribute(name = "consumer.queue.replay.strategy", type = "string", direction = INCOMING, description = "The receiver replay strategy. Supported values all-messages, time-based, replication-group-message-id")
@ConnectorAttribute(name = "consumer.queue.replay.timebased-start-time", type = "string", direction = INCOMING, description = "The receiver replay timebased start time")
@ConnectorAttribute(name = "consumer.queue.replay.replication-group-message-id", type = "string", direction = INCOMING, description = "The receiver replay replication group message id")
@ConnectorAttribute(name = "consumer.failure-strategy", type = "string", direction = INCOMING, description = "Specify the failure strategy to apply when a message consumed from Solace broker is nacked. Accepted values are `ignore` (default), `fail`, `discard`, `error_topic`.", defaultValue = "ignore")
@ConnectorAttribute(name = "consumer.error.topic", type = "string", direction = INCOMING, description = "The error topic where message should be published in case of error")
@ConnectorAttribute(name = "consumer.error.message.dmq-eligible", type = "boolean", direction = INCOMING, description = "Whether error message is eligible to move to dead message queue", defaultValue = "false")
@ConnectorAttribute(name = "consumer.error.message.ttl", type = "long", direction = INCOMING, description = "TTL for Error message before moving to dead message queue.")
@ConnectorAttribute(name = "consumer.error.message.max-delivery-attempts", type = "int", direction = INCOMING, description = "Maximum number of attempts to send a failed message to the error topic in case of failure. Each attempt will have a backoff interval of 1 second. When all delivery attempts have been exhausted, the failed message will be requeued on the queue for redelivery.", defaultValue = "3")
@ConnectorAttribute(name = "consumer.queue.supports-nacks", type = "boolean", direction = INCOMING, description = "Whether to enable negative acknowledgments on failed messages. Nacks are supported on event brokers 10.2.1 and later. If an event broker does not support Nacks, an exception is thrown", defaultValue = "false")

@ConnectorAttribute(name = "producer.topic", type = "string", direction = OUTGOING, description = "The topic to publish messages, by default the channel name")
@ConnectorAttribute(name = "producer.max-inflight-messages", type = "long", direction = OUTGOING, description = "The maximum number of messages to be written to Solace broker. It limits the number of messages waiting to be written and acknowledged by the broker. You can set this attribute to `0` remove the limit", defaultValue = "1024")
@ConnectorAttribute(name = "producer.waitForPublishReceipt", type = "boolean", direction = OUTGOING, description = "Whether the client waits to receive the publish receipt from Solace broker before acknowledging the message", defaultValue = "true")
@ConnectorAttribute(name = "producer.delivery.ack.timeout", type = "int", direction = OUTGOING, description = "Timeout to receive the publish receipt from broker.")
@ConnectorAttribute(name = "producer.delivery.ack.window.size", type = "int", direction = OUTGOING, description = "Publish Window will determine the maximum number of messages the application can send before the Solace API must receive an acknowledgment from the Solace.")
@ConnectorAttribute(name = "producer.back-pressure.strategy", type = "string", direction = OUTGOING, description = "It is possible for the client application to publish messages more quickly than the API can send them to the broker due to network congestion or connectivity issues. This delay can cause the internal buffer to accumulate messages until it reaches its capacity, preventing the API from storing any more messages.", defaultValue = "elastic")
@ConnectorAttribute(name = "producer.back-pressure.buffer-capacity", type = "int", direction = OUTGOING, description = "Outgoing messages backpressure buffer capacity", defaultValue = "1024")
public class SolaceConnector implements InboundConnector, OutboundConnector, HealthReporter {

    public static final String CONNECTOR_NAME = "quarkus-solace";

    @Inject
    ExecutionHolder executionHolder;

    @Inject
    MessagingService solace;

    @Inject
    Instance<OpenTelemetry> openTelemetryInstance;

    Vertx vertx;

    List<SolaceIncomingChannel> incomingChannels = new CopyOnWriteArrayList<>();
    List<SolaceDirectMessageIncomingChannel> directMessageIncomingChannels = new CopyOnWriteArrayList<>();
    List<SolaceOutgoingChannel> outgoingChannels = new CopyOnWriteArrayList<>();
    List<SolaceDirectMessageOutgoingChannel> directMessageOutgoingChannels = new CopyOnWriteArrayList<>();

    public void terminate(
            @Observes(notifyObserver = Reception.IF_EXISTS) @Priority(50) @BeforeDestroyed(ApplicationScoped.class) Object event) {
        incomingChannels.forEach(SolaceIncomingChannel::close);
        directMessageIncomingChannels.forEach(SolaceDirectMessageIncomingChannel::close);
        outgoingChannels.forEach(SolaceOutgoingChannel::close);
        directMessageOutgoingChannels.forEach(SolaceDirectMessageOutgoingChannel::close);
    }

    @PostConstruct
    void init() {
        this.vertx = executionHolder.vertx();
    }

    @Override
    public Flow.Publisher<? extends Message<?>> getPublisher(Config config) {
        var ic = new SolaceConnectorIncomingConfiguration(config);
        if (ic.getClientType().equals("direct")) {
            SolaceDirectMessageIncomingChannel channel = new SolaceDirectMessageIncomingChannel(vertx, openTelemetryInstance,
                    ic, solace);
            directMessageIncomingChannels.add(channel);
            return channel.getStream();
        } else {
            SolaceIncomingChannel channel = new SolaceIncomingChannel(vertx, openTelemetryInstance, ic, solace);
            incomingChannels.add(channel);
            return channel.getStream();
        }
    }

    @Override
    public Flow.Subscriber<? extends Message<?>> getSubscriber(Config config) {
        var oc = new SolaceConnectorOutgoingConfiguration(config);
        if (oc.getClientType().equals("direct")) {
            SolaceDirectMessageOutgoingChannel channel = new SolaceDirectMessageOutgoingChannel(vertx, openTelemetryInstance,
                    oc, solace);
            directMessageOutgoingChannels.add(channel);
            return channel.getSubscriber();
        } else {
            SolaceOutgoingChannel channel = new SolaceOutgoingChannel(vertx, openTelemetryInstance, oc, solace);
            outgoingChannels.add(channel);
            return channel.getSubscriber();
        }
    }

    @Override
    public HealthReport getStartup() {
        HealthReport.HealthReportBuilder builder = HealthReport.builder();
        for (SolaceIncomingChannel in : incomingChannels) {
            in.isStarted(builder);
        }
        for (SolaceDirectMessageIncomingChannel in : directMessageIncomingChannels) {
            in.isStarted(builder);
        }
        for (SolaceOutgoingChannel sink : outgoingChannels) {
            sink.isStarted(builder);
        }
        for (SolaceDirectMessageOutgoingChannel sink : directMessageOutgoingChannels) {
            sink.isStarted(builder);
        }
        return builder.build();
    }

    @Override
    public HealthReport getReadiness() {
        HealthReport.HealthReportBuilder builder = HealthReport.builder();
        for (SolaceIncomingChannel in : incomingChannels) {
            in.isReady(builder);
        }
        for (SolaceDirectMessageIncomingChannel in : directMessageIncomingChannels) {
            in.isReady(builder);
        }
        for (SolaceOutgoingChannel sink : outgoingChannels) {
            sink.isReady(builder);
        }
        for (SolaceDirectMessageOutgoingChannel sink : directMessageOutgoingChannels) {
            sink.isReady(builder);
        }
        return builder.build();

    }

    @Override
    public HealthReport getLiveness() {
        HealthReport.HealthReportBuilder builder = HealthReport.builder();
        for (SolaceIncomingChannel in : incomingChannels) {
            in.isAlive(builder);
        }
        for (SolaceDirectMessageIncomingChannel in : directMessageIncomingChannels) {
            in.isAlive(builder);
        }
        for (SolaceOutgoingChannel out : outgoingChannels) {
            out.isAlive(builder);
        }
        for (SolaceDirectMessageOutgoingChannel out : directMessageOutgoingChannels) {
            out.isAlive(builder);
        }
        return builder.build();
    }
}
