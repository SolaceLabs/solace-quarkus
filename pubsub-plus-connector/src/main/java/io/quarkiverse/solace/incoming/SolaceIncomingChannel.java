package io.quarkiverse.solace.incoming;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

import io.quarkiverse.solace.SolaceConnectorIncomingConfiguration;
import io.quarkiverse.solace.i18n.SolaceLogging;
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
    private final PersistentMessageReceiver receiver;
    private final Flow.Publisher<? extends Message<?>> stream;
    private final ExecutorService pollerThread;
    private SolaceErrorTopicPublisherHandler solaceErrorTopicPublisherHandler;
    private long waitTimeout = -1;

    // Assuming we won't ever exceed the limit of an unsigned long...
    private final UnsignedCounterBarrier unacknowledgedMessageTracker = new UnsignedCounterBarrier();

    public SolaceIncomingChannel(Vertx vertx, SolaceConnectorIncomingConfiguration ic, MessagingService solace) {
        this.channel = ic.getChannel();
        this.context = Context.newInstance(((VertxInternal) vertx.getDelegate()).createEventLoopContext());
        this.waitTimeout = ic.getClientShutdownWaitTimeout();
        DirectMessageReceiver r = solace.createDirectMessageReceiverBuilder().build();
        Outcome[] outcomes = new Outcome[] { Outcome.ACCEPTED };
        if (ic.getConsumerQueueEnableNacks()) {
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
        this.failureHandler = new SolaceFailureHandler(channel, receiver, solace);
        if (ic.getConsumerQueuePublishToErrorTopicOnFailure()) {
            ic.getConsumerQueueErrorTopic().ifPresent(errorTopic -> {
                this.solaceErrorTopicPublisherHandler = new SolaceErrorTopicPublisherHandler(solace, errorTopic);
            });
        }

        Integer timeout = getTimeout(ic.getConsumerQueuePolledWaitTimeInMillis());
        // TODO Here use a subscription receiver.receiveAsync with an internal queue
        this.pollerThread = Executors.newSingleThreadExecutor();
        this.stream = Multi.createBy().repeating()
                .uni(() -> Uni.createFrom().item(timeout == null ? receiver.receiveMessage() : receiver.receiveMessage(timeout))
                        .runSubscriptionOn(pollerThread))
                .until(__ -> closed.get())
                .emitOn(context::runOnContext)
                .map(consumed -> new SolaceInboundMessage<>(consumed, ackHandler, failureHandler,
                        solaceErrorTopicPublisherHandler, ic, unacknowledgedMessageTracker))
                .plug(m -> lazyStart ? m.onSubscription().call(() -> Uni.createFrom().completionStage(receiver.startAsync()))
                        : m)
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3);
        if (!lazyStart) {
            receiver.start();
        }
    }

    private Integer getTimeout(Integer timeoutInMillis) {
        Integer realTimeout;
        final Long expiry = timeoutInMillis != null
                ? timeoutInMillis + System.currentTimeMillis()
                : null;
        if (expiry != null) {
            try {
                realTimeout = Math.toIntExact(expiry - System.currentTimeMillis());
                if (realTimeout < 0) {
                    realTimeout = 0;
                }
            } catch (ArithmeticException e) {
                // Always true: expiry - System.currentTimeMillis() < timeoutInMillis
                // So just set it to 0 (no-wait) if we underflow
                realTimeout = 0;
            }
        } else {
            realTimeout = null;
        }

        return realTimeout;
    }

    private static Queue getQueue(SolaceConnectorIncomingConfiguration ic) {
        String queueType = ic.getConsumerQueueType();
        switch (queueType) {
            case "durable-non-exclusive":
                return Queue.durableNonExclusiveQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
            case "durable-exclusive":
                return Queue.durableExclusiveQueue(ic.getConsumerQueueName().orElse(ic.getChannel()));
            default:
            case "non-durable-exclusive":
                return ic.getConsumerQueueName().map(Queue::nonDurableExclusiveQueue)
                        .orElseGet(Queue::nonDurableExclusiveQueue);

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
            if (!unacknowledgedMessageTracker.awaitEmpty(this.waitTimeout, TimeUnit.MILLISECONDS)) {
                SolaceLogging.log.info(String.format("Timed out while waiting for the" +
                        " remaining messages to be acknowledged."));
            }
        } catch (InterruptedException e) {
            SolaceLogging.log.info(String.format("Interrupted while waiting for messages to get acknowledged"));
            throw new RuntimeException(e);
        }
    }

    public void close() {
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

    }

    public void isReady(HealthReport.HealthReportBuilder builder) {

    }

    public void isAlive(HealthReport.HealthReportBuilder builder) {

    }

    @Override
    public void onStateChange(ReceiverState receiverState, ReceiverState receiverState1, long l) {

    }
}
