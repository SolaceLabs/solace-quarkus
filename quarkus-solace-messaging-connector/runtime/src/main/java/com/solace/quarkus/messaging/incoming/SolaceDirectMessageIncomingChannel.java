package com.solace.quarkus.messaging.incoming;

import static com.solace.quarkus.messaging.i18n.SolaceExceptions.ex;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.DirectMessageReceiverBuilder;
import com.solace.messaging.MessagingService;
import com.solace.messaging.config.*;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.SolaceConnectorIncomingConfiguration;
import com.solace.quarkus.messaging.fault.*;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.tracing.SolaceOpenTelemetryInstrumenter;
import com.solace.quarkus.messaging.tracing.SolaceTrace;

import io.opentelemetry.api.OpenTelemetry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.vertx.core.impl.VertxInternal;
import io.vertx.mutiny.core.Context;
import io.vertx.mutiny.core.Vertx;

public class SolaceDirectMessageIncomingChannel
        implements ReceiverActivationPassivationConfiguration.ReceiverStateChangeListener {
    private final String channel;
    private final Context context;
    private final SolaceAckHandler ackHandler;
    private final SolaceFailureHandler failureHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final DirectMessageReceiver receiver;
    private final Flow.Publisher<? extends Message<?>> stream;
    private final ExecutorService pollerThread;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final List<Throwable> failures = new ArrayList<>();
    private final SolaceOpenTelemetryInstrumenter solaceOpenTelemetryInstrumenter;
    private final MessagingService solace;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker = new IncomingMessagesUnsignedCounterBarrier();

    public SolaceDirectMessageIncomingChannel(Vertx vertx, Instance<OpenTelemetry> openTelemetryInstance,
            SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
        this.solace = solace;
        this.channel = ic.getChannel();
        this.context = Context.newInstance(((VertxInternal) vertx.getDelegate()).createEventLoopContext());
        this.gracefulShutdown = ic.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = ic.getClientGracefulShutdownWaitTimeout();
        MessageAcknowledgementConfiguration.Outcome[] outcomes = new MessageAcknowledgementConfiguration.Outcome[] {
                MessageAcknowledgementConfiguration.Outcome.ACCEPTED };

        DirectMessageReceiverBuilder builder = solace.createDirectMessageReceiverBuilder();
        String subscriptions = ic.getConsumerSubscriptions().orElse(this.channel);
        builder.withSubscriptions(Arrays.stream(subscriptions.split(","))
                .map(TopicSubscription::of)
                .toArray(TopicSubscription[]::new));

        switch (ic.getClientTypeDirectBackPressureStrategy()) {
            case "oldest":
                builder.onBackPressureDropLatest(ic.getClientTypeDirectBackPressureBufferCapacity());
                break;
            case "latest":
                builder.onBackPressureDropOldest(ic.getClientTypeDirectBackPressureBufferCapacity());
                break;
            default:
                builder.onBackPressureElastic();
                break;
        }

        this.receiver = builder.build();

        boolean lazyStart = ic.getClientLazyStart();
        this.ackHandler = null;
        this.failureHandler = createFailureHandler(ic, solace);

        // TODO Here use a subscription receiver.receiveAsync with an internal queue
        this.pollerThread = Executors.newSingleThreadExecutor();

        Multi<? extends Message<?>> incomingMulti = Multi.createBy().repeating()
                .uni(() -> Uni.createFrom().item(this.receiver::receiveMessage)
                        .runSubscriptionOn(pollerThread))
                .until(__ -> closed.get())
                .emitOn(context::runOnContext)
                .map(consumed -> new SolaceInboundMessage<>(consumed, ackHandler, failureHandler,
                        unacknowledgedMessageTracker, this::reportFailure));

        if (ic.getClientTracingEnabled()) {
            solaceOpenTelemetryInstrumenter = SolaceOpenTelemetryInstrumenter.createForIncoming(openTelemetryInstance);
            incomingMulti = incomingMulti.map(message -> {
                InboundMessage consumedMessage = message.getMetadata(SolaceInboundMetadata.class).get().getMessage();
                Map<String, String> messageProperties = new HashMap<>();

                messageProperties.put("messaging.solace.replication_group_message_id",
                        consumedMessage.getReplicationGroupMessageId().toString());
                messageProperties.put("messaging.solace.priority", Integer.toString(consumedMessage.getPriority()));
                if (consumedMessage.getProperties().size() > 0) {
                    messageProperties.putAll(consumedMessage.getProperties());
                }
                SolaceTrace solaceTrace = new SolaceTrace.Builder()
                        .withDestinationKind("queue")
                        .withTopic(consumedMessage.getDestinationName())
                        .withMessageID(consumedMessage.getApplicationMessageId())
                        .withCorrelationID(consumedMessage.getCorrelationId())
                        .withPartitionKey(
                                consumedMessage
                                        .hasProperty(SolaceConstants.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                ? consumedMessage
                                                        .getProperty(
                                                                SolaceConstants.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)
                                                : null)
                        .withPayloadSize(Long.valueOf(consumedMessage.getPayloadAsBytes().length))
                        .withProperties(messageProperties)
                        .build();
                return solaceOpenTelemetryInstrumenter.traceIncoming(message, solaceTrace, true);
            });
        } else {
            solaceOpenTelemetryInstrumenter = null;
        }

        this.stream = incomingMulti.plug(m -> lazyStart
                ? m.onSubscription()
                        .call(() -> Uni.createFrom().completionStage(this.receiver.startAsync()))
                : m)
                .onItem().invoke(() -> alive.set(true))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3).onFailure().invoke(this::reportFailure);

        if (!lazyStart) {
            this.receiver.start();
        }

    }

    private synchronized void reportFailure(Throwable throwable) {
        alive.set(false);
        // Don't keep all the failures, there are only there for reporting.
        if (failures.size() == 10) {
            failures.remove(0);
        }
        failures.add(throwable);
    }

    private SolaceFailureHandler createFailureHandler(SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
        String strategy = ic.getConsumerFailureStrategy();
        SolaceFailureHandler.Strategy actualStrategy = SolaceFailureHandler.Strategy.from(strategy);
        switch (actualStrategy) {
            case IGNORE:
                return new SolaceIgnoreFailure(ic.getChannel());
            case ERROR_TOPIC:
                if (ic.getConsumerErrorTopic().isEmpty()) {
                    throw ex.illegalArgumentInvalidFailureStrategy(strategy);
                }
                return new SolaceErrorTopic(ic.getChannel(), ic.getConsumerErrorTopic().get(),
                        ic.getConsumerErrorMessageDmqEligible(), ic.getConsumerErrorMessageTtl().orElse(null),
                        ic.getConsumerErrorMessageMaxDeliveryAttempts(), null, solace);
            default:
                throw ex.illegalArgumentInvalidFailureStrategy(
                        "Direct Consumer supports 'ignore' and 'error_topic' failure strategies. Please check your configured failure strategy :: "
                                + strategy);
        }

    }

    public Flow.Publisher<? extends Message<?>> getStream() {
        return this.stream;
    }

    public void waitForUnAcknowledgedMessages() {
        try {
            receiver.terminate(3000);
            SolaceLogging.log.infof("Waiting for incoming channel %s messages to be acknowledged", channel);
            if (!unacknowledgedMessageTracker.awaitEmpty(this.gracefulShutdownWaitTimeout, TimeUnit.MILLISECONDS)) {
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
            waitForUnAcknowledgedMessages();
        }
        closed.compareAndSet(false, true);
        if (this.pollerThread != null) {
            if (this.gracefulShutdown) {
                this.pollerThread.shutdown();
                try {
                    this.pollerThread.awaitTermination(3000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    SolaceLogging.log.shutdownException(e.getMessage());
                    throw new RuntimeException(e);
                }
            } else {
                this.pollerThread.shutdownNow();
            }
        }
        if (receiver.isRunning()) {
            receiver.terminate(3000);
        }
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected());
    }

    public void isReady(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected() && receiver != null && receiver.isRunning());
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
    public void onStateChange(ReceiverState receiverState, ReceiverState receiverState1, long l) {

    }
}
