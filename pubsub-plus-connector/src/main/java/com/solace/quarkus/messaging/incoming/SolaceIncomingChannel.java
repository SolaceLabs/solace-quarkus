package com.solace.quarkus.messaging.incoming;

import static com.solace.quarkus.messaging.i18n.SolaceExceptions.ex;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.MessagingService;
import com.solace.messaging.PersistentMessageReceiverBuilder;
import com.solace.messaging.config.MessageAcknowledgementConfiguration.Outcome;
import com.solace.messaging.config.MissingResourcesCreationConfiguration.MissingResourcesCreationStrategy;
import com.solace.messaging.config.ReceiverActivationPassivationConfiguration;
import com.solace.messaging.config.ReplayStrategy;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.SolaceConnectorIncomingConfiguration;
import com.solace.quarkus.messaging.fault.*;
import com.solace.quarkus.messaging.i18n.SolaceLogging;

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
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final PersistentMessageReceiver receiver;
    private final Flow.Publisher<? extends Message<?>> stream;
    private final ExecutorService pollerThread;
    private final boolean gracefulShutdown;
    private final long gracefulShutdownWaitTimeout;
    private final List<Throwable> failures = new ArrayList<>();
    private volatile MessagingService solace;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final IncomingMessagesUnsignedCounterBarrier unacknowledgedMessageTracker = new IncomingMessagesUnsignedCounterBarrier();

    public SolaceIncomingChannel(Vertx vertx, SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
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
            String subscriptions = ic.getConsumerQueueSubscriptions().orElse(this.channel);
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
        this.stream = Multi.createBy().repeating()
                .uni(() -> Uni.createFrom().item(receiver::receiveMessage)
                        .runSubscriptionOn(pollerThread))
                .until(__ -> closed.get())
                .emitOn(context::runOnContext)
                .map(consumed -> new SolaceInboundMessage<>(consumed, ackHandler, failureHandler,
                        unacknowledgedMessageTracker, this::reportFailure))
                .plug(m -> lazyStart
                        ? m.onSubscription()
                                .call(() -> Uni.createFrom().completionStage(receiver.startAsync()))
                        : m)
                .onItem().invoke(() -> alive.set(true))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3).onFailure().invoke((t) -> {
                    failures.add(t);
                    alive.set(false);
                });
        if (!lazyStart) {
            receiver.start();
        }
    }

    private void reportFailure(Throwable throwable) {
        failures.add(throwable);
        alive.set(false);
    }

    private SolaceFailureHandler createFailureHandler(SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
        String strategy = ic.getConsumerQueueFailureStrategy();
        SolaceFailureHandler.Strategy actualStrategy = SolaceFailureHandler.Strategy.from(strategy);
        switch (actualStrategy) {
            case IGNORE:
                return new SolaceIgnoreFailure(ic.getChannel());
            case FAIL:
                return new SolaceFail(ic.getChannel(), receiver);
            case DISCARD:
                return new SolaceDiscard(ic.getChannel(), receiver);
            case ERROR_TOPIC:
                SolaceErrorTopic solaceErrorTopic = new SolaceErrorTopic(ic.getChannel(), receiver, solace);
                if (ic.getConsumerQueueErrorTopic().isEmpty()) {
                    throw ex.illegalArgumentInvalidFailureStrategy(strategy);
                }
                solaceErrorTopic.setErrorTopic(ic.getConsumerQueueErrorTopic().get());
                solaceErrorTopic.setDmqEligible(ic.getConsumerQueueErrorMessageDmqEligible().booleanValue());
                solaceErrorTopic.setTimeToLive(ic.getConsumerQueueErrorMessageTtl().orElse(null));
                solaceErrorTopic.setMaxDeliveryAttempts(ic.getConsumerQueueErrorMessageMaxDeliveryAttempts());
                return solaceErrorTopic;
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
            this.pollerThread.shutdown();
            try {
                this.pollerThread.awaitTermination(3000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                SolaceLogging.log.shutdownException(e.getMessage());
                throw new RuntimeException(e);
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
            failures.removeAll(reportedFailures);
        } else {
            builder.add(channel, solace.isConnected() && alive.get());
        }
    }

    @Override
    public void onStateChange(ReceiverState receiverState, ReceiverState receiverState1, long l) {

    }
}
