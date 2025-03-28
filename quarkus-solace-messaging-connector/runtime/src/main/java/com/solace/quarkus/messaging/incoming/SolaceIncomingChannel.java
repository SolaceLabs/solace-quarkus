package com.solace.quarkus.messaging.incoming;

import static com.solace.quarkus.messaging.i18n.SolaceExceptions.ex;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PersistentMessageReceiverBuilder;
import com.solace.messaging.config.MessageAcknowledgementConfiguration.Outcome;
import com.solace.messaging.config.MissingResourcesCreationConfiguration.MissingResourcesCreationStrategy;
import com.solace.messaging.config.ReceiverActivationPassivationConfiguration;
import com.solace.messaging.config.ReplayStrategy;
import com.solace.messaging.config.SolaceConstants;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
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

public class SolaceIncomingChannel implements ReceiverActivationPassivationConfiguration.ReceiverStateChangeListener {

    private final String channel;
    private final Context context;
    private final SolaceAckHandler ackHandler;
    private final SolaceFailureHandler failureHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final PersistentMessageReceiver receiver;
    private final Flow.Publisher<? extends Message<?>> stream;
    private final ExecutorService pollerThread;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final List<Throwable> failures = new ArrayList<>();
    private final SolaceOpenTelemetryInstrumenter solaceOpenTelemetryInstrumenter;
    private volatile MessagingService solace;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker = new IncomingMessagesUnsignedCounterBarrier();

    public SolaceIncomingChannel(Vertx vertx, Instance<OpenTelemetry> openTelemetryInstance,
            SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
        this.solace = solace;
        this.channel = ic.getChannel();
        this.context = Context.newInstance(((VertxInternal) vertx.getDelegate()).createEventLoopContext());
        this.gracefulShutdown = ic.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = ic.getClientGracefulShutdownWaitTimeout();
        Outcome[] outcomes = new Outcome[] { Outcome.ACCEPTED };
        if (ic.getConsumerQueueSupportsNacks()) {
            outcomes = new Outcome[] { Outcome.ACCEPTED, Outcome.FAILED, Outcome.REJECTED };
        }
        PersistentMessageReceiverBuilder builder = solace.createPersistentMessageReceiverBuilder()
                .withMessageClientAcknowledgement()
                .withRequiredMessageClientOutcomeOperationSupport(outcomes)
                .withActivationPassivationSupport(this);

        ic.getConsumerQueueSelectorQuery().ifPresent(builder::withMessageSelector);
        ic.getConsumerQueueReplayStrategy().ifPresent(s -> {
            switch (s) {
                case "all-messages":
                    builder.withMessageReplay(ReplayStrategy.allMessages());
                    break;
                case "time-based":
                    builder.withMessageReplay(getTimeBasedReplayStrategy(ic));
                    break;
                case "replication-group-message-id":
                    builder.withMessageReplay(getGroupMessageIdReplayStrategy(ic));
                    break;
            }
        });
        if (ic.getConsumerQueueAddAdditionalSubscriptions()) {
            String subscriptions = ic.getConsumerSubscriptions().orElse(this.channel);
            builder.withSubscriptions(Arrays.stream(subscriptions.split(","))
                    .map(TopicSubscription::of)
                    .toArray(TopicSubscription[]::new));
        }
        switch (ic.getConsumerQueueMissingResourceCreationStrategy()) {
            case "create-on-start":
                builder.withMissingResourcesCreationStrategy(MissingResourcesCreationStrategy.CREATE_ON_START);
                break;
            case "do-not-create":
                builder.withMissingResourcesCreationStrategy(MissingResourcesCreationStrategy.DO_NOT_CREATE);
                break;
        }

        this.receiver = builder.build(getQueue(ic));
        boolean lazyStart = ic.getClientLazyStart();
        this.ackHandler = new SolaceAckHandler(receiver);
        this.failureHandler = createFailureHandler(ic, solace);

        // TODO Here use a subscription receiver.receiveAsync with an internal queue
        this.pollerThread = Executors.newSingleThreadExecutor();

        Multi<? extends Message<?>> incomingMulti = Multi.createBy().repeating()
                .uni(() -> Uni.createFrom().item(receiver::receiveMessage)
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
                if (!consumedMessage.getProperties().isEmpty()) {
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
                        .withPayloadSize((long) consumedMessage.getPayloadAsBytes().length)
                        .withProperties(messageProperties)
                        .build();
                return solaceOpenTelemetryInstrumenter.traceIncoming(message, solaceTrace, true);
            });
        } else {
            solaceOpenTelemetryInstrumenter = null;
        }

        this.stream = incomingMulti.plug(m -> lazyStart
                ? m.onSubscription()
                        .call(() -> Uni.createFrom().completionStage(receiver.startAsync()))
                : m)
                .onItem().invoke(() -> alive.set(true))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3).onFailure().invoke(this::reportFailure);

        if (!lazyStart) {
            receiver.start();
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
            case FAIL:
                return new SolaceFail(ic.getChannel(), receiver);
            case DISCARD:
                return new SolaceDiscard(ic.getChannel(), receiver);
            case ERROR_TOPIC:
                if (ic.getConsumerErrorTopic().isEmpty()) {
                    throw ex.illegalArgumentInvalidFailureStrategy(strategy);
                }
                return new SolaceErrorTopic(ic.getChannel(), ic.getConsumerErrorTopic().get(),
                        ic.getConsumerErrorMessageDmqEligible(), ic.getConsumerErrorMessageTtl().orElse(null),
                        ic.getConsumerErrorMessageMaxDeliveryAttempts(), receiver, solace);
            default:
                throw ex.illegalArgumentInvalidFailureStrategy(strategy);
        }

    }

    private static Queue getQueue(SolaceConnectorIncomingConfiguration ic) {
        String queueType = ic.getConsumerQueueType();
        switch (queueType) {
            case "durable-non-exclusive":
                return Queue.durableNonExclusiveQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
            case "non-durable-exclusive":
                return ic.getConsumerQueueName().map(Queue::nonDurableExclusiveQueue)
                        .orElseGet(Queue::nonDurableExclusiveQueue);
            default:
            case "durable-exclusive":
                return Queue.durableExclusiveQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));

        }
    }

    private static ReplayStrategy getGroupMessageIdReplayStrategy(SolaceConnectorIncomingConfiguration ic) {
        String groupMessageId = ic.getConsumerQueueReplayReplicationGroupMessageId().orElseThrow();
        return ReplayStrategy.replicationGroupMessageIdBased(InboundMessage.ReplicationGroupMessageId.of(groupMessageId));
    }

    private static ReplayStrategy getTimeBasedReplayStrategy(SolaceConnectorIncomingConfiguration ic) {
        String zoneDateTime = ic.getConsumerQueueReplayTimebasedStartTime().orElseThrow();
        return ReplayStrategy.timeBased(ZonedDateTime.parse(zoneDateTime));
    }

    public Flow.Publisher<? extends Message<?>> getStream() {
        return this.stream;
    }

    public void waitForUnAcknowledgedMessages() {
        try {
            receiver.pause();
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
        receiver.terminate(3000);
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
        SolaceLogging.log.infof("Consumer state changed from %s to %s on channel %s", receiverState.name(),
                receiverState1.name(), channel);
    }
}
