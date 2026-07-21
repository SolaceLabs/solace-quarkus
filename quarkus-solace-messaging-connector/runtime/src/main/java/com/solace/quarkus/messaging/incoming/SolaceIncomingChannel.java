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
    // receiver is rebuilt on reconnect, so it can't be final. volatile so the
    // poller thread sees the new instance immediately after a rebuild.
    private volatile PersistentMessageReceiver receiver;
    // Config kept so the receiver can be rebuilt with identical settings on reconnect.
    private final SolaceConnectorIncomingConfiguration ic;
    // Guards against overlapping rebuilds if multiple reconnect events arrive.
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);
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
        this.ic = ic;
        this.channel = ic.getChannel();
        this.context = Context.newInstance(((VertxInternal) vertx.getDelegate()).createEventLoopContext());
        this.gracefulShutdown = ic.getClientGracefulShutdown();
        this.gracefulShutdownWaitTimeout = ic.getClientGracefulShutdownWaitTimeout();

        this.receiver = buildReceiver();
        boolean lazyStart = ic.getClientLazyStart();
        // Supplier so the ack handler always targets the CURRENT receiver, which
        // changes when the receiver is rebuilt on reconnect.
        this.ackHandler = new SolaceAckHandler(() -> this.receiver);
        this.failureHandler = createFailureHandler(ic, solace);

        // TODO Here use a subscription receiver.receiveAsync with an internal queue
        this.pollerThread = Executors.newSingleThreadExecutor();

        Multi<? extends Message<?>> incomingMulti = Multi.createBy().repeating()
                // FIX: use receiveMessage(timeout) instead of the no-arg blocking variant.
                // The no-arg receiveMessage() blocks the single poller thread forever. When
                // the session reconnects (e.g. after an OAuth token refresh / broker-initiated
                // close), the blocking call against the old flow never returns and the poller
                // never resumes pulling from the re-established flow — consumption stops
                // permanently. With a 1s timeout the poller wakes up regularly, so once the
                // Solace API rebinds the flow after reconnect, the next poll picks up messages
                // again and consumption resumes automatically.
                .uni(() -> Uni.createFrom().item(() -> this.receiver.receiveMessage(1000))
                        .runSubscriptionOn(pollerThread))
                .until(__ -> closed.get())
                .emitOn(context::runOnContext)
                // receiveMessage(timeout) returns null when no message arrives within the
                // timeout (idle, or flow temporarily unavailable during reconnect). Drop the
                // nulls so they never reach the mapping step — a null would NPE in
                // convertPayload() and, via the retry-then-report path below, terminate the
                // whole stream.
                .filter(Objects::nonNull)
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
                        .call(() -> Uni.createFrom().completionStage(this.receiver.startAsync()))
                : m)
                .onItem().invoke(() -> alive.set(true))
                // FIX: retry indefinitely instead of atMost(3). Previously, three consecutive
                // failures (which is what happens when the flow throws during a reconnect
                // window) terminated the stream for good via reportFailure(), so consumption
                // never recovered even after the session reconnected successfully. Retrying
                // indefinitely with a capped backoff means a transient reconnect exception is
                // survived: the poller keeps retrying until the flow is available again.
                // reportFailure() is still invoked on each failure so health reporting and the
                // failures list stay accurate, but the stream is never permanently terminated.
                .onFailure().invoke(this::reportFailure)
                .onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(10)).indefinitely();

        if (!lazyStart) {
            this.receiver.start();
        }

        // FIX (DATAGO-141425): rebuild the receiver after the session reconnects.
        // After an OAuth-triggered "Channel is closed by peer" the transport
        // reconnects successfully, but the consumer flow bound to the old session
        // is dead. POLL-TRACE confirmed that calling receiver.start() on the
        // already-started receiver is a no-op: the poller keeps calling
        // receiveMessage() and gets null forever because the flow was never
        // rebound. The only reliable recovery is to terminate the stale receiver
        // and build+start a fresh one bound to the new session.
        // addReconnectionListener fires AFTER a successful reconnect (unlike
        // addReconnectionAttemptListener which fires during the attempt).
        solace.addReconnectionListener(serviceEvent -> {
            SolaceLogging.log.infof("Session reconnected on channel %s", channel);
            rebuildReceiverAfterReconnect();
        });
    }

    /**
     * Builds a fresh {@link PersistentMessageReceiver} with the channel's
     * configured settings. Called once at construction and again on every
     * reconnect to fully re-establish the consumer flow — calling
     * {@code receiver.start()} on an already-started receiver is a no-op and
     * does NOT rebind the flow to a reconnected session (confirmed by
     * POLL-TRACE showing receiveMessage() returning null indefinitely after
     * reconnect). Rebuilding is the only reliable way to bind a new flow.
     */
    private PersistentMessageReceiver buildReceiver() {
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
        return builder.build(getQueue(ic));
    }

    /**
     * Rebuilds and restarts the receiver after a session reconnect. Terminates
     * the stale receiver (whose flow is bound to the dead session), builds a
     * fresh one, and starts it so the poller resumes pulling from the new flow.
     */
    private void rebuildReceiverAfterReconnect() {
        if (closed.get()) {
            return;
        }
        // Prevent overlapping rebuilds if several reconnect events fire together.
        if (!rebuilding.compareAndSet(false, true)) {
            return;
        }
        try {
            SolaceLogging.log.infof("Rebuilding receiver on channel %s after reconnect", channel);
            PersistentMessageReceiver old = this.receiver;
            try {
                if (old != null) {
                    old.terminate(2000);
                }
            } catch (Throwable t) {
                SolaceLogging.log.infof("Ignoring error terminating stale receiver on channel %s: %s",
                        channel, t.getMessage());
            }
            PersistentMessageReceiver fresh = buildReceiver();
            fresh.start();
            // volatile write — the poller lambda reads `receiver` each iteration
            // and will pull from this fresh instance on its next poll.
            this.receiver = fresh;
            alive.set(true);
            SolaceLogging.log.infof("Receiver rebuilt and started on channel %s — consumption resumed", channel);
        } catch (Throwable t) {
            SolaceLogging.log.errorf(t, "Failed to rebuild receiver on channel %s after reconnect", channel);
        } finally {
            rebuilding.set(false);
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
                return new SolaceFail(ic.getChannel(), () -> this.receiver);
            case DISCARD:
                return new SolaceDiscard(ic.getChannel(), () -> this.receiver);
            case ERROR_TOPIC:
                if (ic.getConsumerErrorTopic().isEmpty()) {
                    throw ex.illegalArgumentInvalidFailureStrategy(strategy);
                }
                return new SolaceErrorTopic(ic.getChannel(), ic.getConsumerErrorTopic().get(),
                        ic.getConsumerErrorMessageDmqEligible(), ic.getConsumerErrorMessageTtl().orElse(null),
                        ic.getConsumerErrorMessageMaxDeliveryAttempts(), () -> this.receiver, solace);
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
            this.receiver.pause();
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
        this.receiver.terminate(3000);
    }

    public void isStarted(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected());
    }

    public void isReady(HealthReport.HealthReportBuilder builder) {
        builder.add(channel, solace.isConnected() && this.receiver != null && this.receiver.isRunning());
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