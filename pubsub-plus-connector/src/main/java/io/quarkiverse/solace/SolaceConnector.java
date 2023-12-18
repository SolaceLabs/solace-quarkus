package io.quarkiverse.solace;

import static io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction.INCOMING;
import static io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction.INCOMING_AND_OUTGOING;
import static io.smallrye.reactive.messaging.annotations.ConnectorAttribute.Direction.OUTGOING;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Reception;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;

import com.solace.messaging.MessagingService;

import io.quarkiverse.solace.i18n.SolaceLogging;
import io.quarkiverse.solace.incoming.SolaceIncomingChannel;
import io.quarkiverse.solace.outgoing.SolaceOutgoingChannel;
import io.quarkus.runtime.ShutdownEvent;
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
//@ConnectorAttribute(name = "client.type", type = "string", direction = INCOMING_AND_OUTGOING, description = "Direct or persisted", defaultValue = "persisted")
@ConnectorAttribute(name = "client.lazy.start", type = "boolean", direction = INCOMING_AND_OUTGOING, description = "Whether the receiver or publisher is started at initialization or lazily at subscription time", defaultValue = "false")
@ConnectorAttribute(name = "client.shutdown.wait-timeout", type = "long", direction = INCOMING_AND_OUTGOING, description = "Timeout in milliseconds to wait for messages to finish processing before shutdown", defaultValue = "10000")
@ConnectorAttribute(name = "consumer.queue.enable-nacks", type = "boolean", direction = INCOMING, description = "Whether to enable negative acknowledgments on failed messages. Nacks are supported on event brokers 10.2.1 and later. If an event broker does not support Nacks, an exception is thrown", defaultValue = "false")
@ConnectorAttribute(name = "consumer.queue.add-additional-subscriptions", type = "boolean", direction = INCOMING, description = "Whether to add configured subscriptions to queue. Will fail if permissions to configure subscriptions is not allowed on broker", defaultValue = "false")
@ConnectorAttribute(name = "consumer.queue.subscriptions", type = "string", direction = INCOMING, description = "The comma separated list of subscriptions, the channel name if empty")
@ConnectorAttribute(name = "consumer.queue.type", type = "string", direction = INCOMING, description = "The queue type of receiver", defaultValue = "durable-non-exclusive")
@ConnectorAttribute(name = "consumer.queue.name", type = "string", direction = INCOMING, description = "The queue name of receiver")
// TODO implement consumer concurrency
//@ConnectorAttribute(name = "consumer.queue.concurrency", type = "int", direction = INCOMING, description = "The number of concurrent consumers", defaultValue = "1")
@ConnectorAttribute(name = "consumer.queue.polled-wait-time-in-millis", type = "int", direction = INCOMING, description = "Maximum wait time for polled consumers to receive a message from configured queue", defaultValue = "100")
@ConnectorAttribute(name = "consumer.queue.missing-resource-creation-strategy", type = "string", direction = INCOMING, description = "Missing resource creation strategy", defaultValue = "do-not-create")
@ConnectorAttribute(name = "consumer.queue.selector-query", type = "string", direction = INCOMING, description = "The receiver selector query")
@ConnectorAttribute(name = "consumer.queue.replay.strategy", type = "string", direction = INCOMING, description = "The receiver replay strategy")
@ConnectorAttribute(name = "consumer.queue.replay.timebased-start-time", type = "string", direction = INCOMING, description = "The receiver replay timebased start time")
@ConnectorAttribute(name = "consumer.queue.replay.replication-group-message-id", type = "string", direction = INCOMING, description = "The receiver replay replication group message id")
@ConnectorAttribute(name = "consumer.queue.discard-messages-on-failure", type = "boolean", direction = INCOMING, description = "Whether discard messages from queue on failure. A negative acknowledgment of type REJECTED is sent to broker which discards the messages from queue and will move to DMQ if enabled. This option works only when enable-nacks is true and error topic is not configured", defaultValue = "false")
@ConnectorAttribute(name = "consumer.queue.publish-to-error-topic-on-failure", type = "boolean", direction = INCOMING, description = "Whether to publish consumed message to error topic on failure", defaultValue = "false")
@ConnectorAttribute(name = "consumer.queue.error.topic", type = "string", direction = INCOMING, description = "The error topic where message should be published in case of error")
@ConnectorAttribute(name = "consumer.queue.error.message.dmq-eligible", type = "boolean", direction = INCOMING, description = "Whether error message is eligible to move to dead message queue", defaultValue = "false")
@ConnectorAttribute(name = "consumer.queue.error.message.ttl", type = "long", direction = INCOMING, description = "Error message TTL before moving to dead message queue")
@ConnectorAttribute(name = "consumer.queue.error.message.max-delivery-attempts", type = "int", direction = INCOMING, description = "Maximum number of attempts to send a failed message to the error topic in case of failure. Each attempt will have a backoff interval of 1 second. When all delivery attempts have been exhausted, the failed message will be requeued on the queue for redelivery.", defaultValue = "3")

@ConnectorAttribute(name = "producer.topic", type = "string", direction = OUTGOING, description = "The topic to publish messages, by default the channel name")
@ConnectorAttribute(name = "producer.max-inflight-messages", type = "long", direction = OUTGOING, description = "The maximum number of messages to be written to Solace broker. It limits the number of messages waiting to be written and acknowledged by the broker. You can set this attribute to `0` remove the limit", defaultValue = "1024")
@ConnectorAttribute(name = "producer.waitForPublishReceipt", type = "boolean", direction = OUTGOING, description = "Whether the client waits to receive the publish receipt from Solace broker before acknowledging the message", defaultValue = "true")
@ConnectorAttribute(name = "producer.delivery.ack.timeout", type = "int", direction = OUTGOING, description = "Delivery ack timeout")
@ConnectorAttribute(name = "producer.delivery.ack.window.size", type = "int", direction = OUTGOING, description = "Delivery ack window size")
@ConnectorAttribute(name = "producer.back-pressure.strategy", type = "string", direction = OUTGOING, description = "Outgoing messages backpressure strategy", defaultValue = "reject")
@ConnectorAttribute(name = "producer.back-pressure.buffer-capacity", type = "int", direction = OUTGOING, description = "Outgoing messages backpressure buffer capacity", defaultValue = "1024")
public class SolaceConnector implements InboundConnector, OutboundConnector, HealthReporter {

    public static final String CONNECTOR_NAME = "quarkus-solace";

    @Inject
    ExecutionHolder executionHolder;

    @Inject
    MessagingService solace;

    Vertx vertx;

    List<SolaceIncomingChannel> incomingChannels = new CopyOnWriteArrayList<>();
    List<SolaceOutgoingChannel> outgoingChannels = new CopyOnWriteArrayList<>();

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        if (solace.isConnected()) {
            SolaceLogging.log.info("Waiting incoming channel messages to be acknowledged");
            incomingChannels.forEach(SolaceIncomingChannel::waitForUnAcknowledgedMessages);
            SolaceLogging.log.info("All incoming channel messages are acknowledged");

            SolaceLogging.log.info("Waiting for outgoing messages to be published");
            outgoingChannels.forEach(SolaceOutgoingChannel::waitForPublishedMessages);
            SolaceLogging.log.info("All outgoing messages are published");
        }
    }

    public void terminate(
            @Observes(notifyObserver = Reception.IF_EXISTS) @Priority(50) @BeforeDestroyed(ApplicationScoped.class) Object event) {
        incomingChannels.forEach(SolaceIncomingChannel::close);
        outgoingChannels.forEach(SolaceOutgoingChannel::close);
    }

    @PostConstruct
    void init() {
        this.vertx = executionHolder.vertx();
    }

    @Override
    public Flow.Publisher<? extends Message<?>> getPublisher(Config config) {
        var ic = new SolaceConnectorIncomingConfiguration(config);
        SolaceIncomingChannel channel = new SolaceIncomingChannel(vertx, ic, solace);
        incomingChannels.add(channel);
        return channel.getStream();
    }

    @Override
    public Flow.Subscriber<? extends Message<?>> getSubscriber(Config config) {
        var oc = new SolaceConnectorOutgoingConfiguration(config);
        SolaceOutgoingChannel channel = new SolaceOutgoingChannel(vertx, oc, solace);
        outgoingChannels.add(channel);
        return channel.getSubscriber();
    }

    @Override
    public HealthReport getStartup() {
        HealthReport.HealthReportBuilder builder = HealthReport.builder();
        for (SolaceIncomingChannel in : incomingChannels) {
            in.isStarted(builder);
        }
        for (SolaceOutgoingChannel sink : outgoingChannels) {
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
        for (SolaceOutgoingChannel sink : outgoingChannels) {
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
        for (SolaceOutgoingChannel out : outgoingChannels) {
            out.isAlive(builder);
        }
        return builder.build();
    }
}
