package com.solace.quarkus.messaging.perf;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.Topic;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

@Disabled
public class EndToEndPerformanceTest extends WeldTestBase {

    private static final int COUNT = 100000;

    private static final int TIMEOUT_IN_SECONDS = 400;

    @Test
    public void endToEndPerformanceTesttWithBackPressureWaitAndWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic);

        // Run app that consumes messages
        runApplication(config, MyProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @Test
    public void endToEndPerformanceTesttWithBackPressureWaitAndNoWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        // Run app that consumes messages
        runApplication(config, MyProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    //
    @Test
    public void endToEndPerformanceTesttWithBackPressureElasticAndWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic");

        // Run app that consumes messages
        runApplication(config, MyProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    //
    @Test
    public void endToEndPerformanceTesttWithBackPressureElasticAndNoWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic")
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        // Run app that consumes messages
        runApplication(config, MyProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @ApplicationScoped
    static class MyProcessor {
        @Incoming("in")
        @Outgoing("out")
        Multi<Message<String>> in(SolaceInboundMessage<byte[]> msg) {
            //            return messagingService.messageBuilder().build(payload);
            return Multi.createFrom().items(msg.getMessage().getPayloadAsString())
                    .map(p -> Message.of(p).withAck(() -> {
                        msg.ack();
                        return CompletableFuture.completedFuture(null);
                    }));
        }

    }

    @Test
    public void endToEndBlockingProcessorPerformanceTesttWithBackPressureWaitAndWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic);

        // Run app that consumes messages
        runApplication(config, MyBlockingProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @Test
    public void endToEndBlockingProcessorPerformanceTesttWithBackPressureWaitAndNoWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        // Run app that consumes messages
        runApplication(config, MyBlockingProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @Test
    public void endToEndBlockingProcessorPerformanceTesttWithBackPressureElasticAndWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic");

        // Run app that consumes messages
        runApplication(config, MyBlockingProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @Test
    public void endToEndBlockingProcessorPerformanceTesttWithBackPressureElasticAndNoWaitForPublishReceipt() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic")
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        // Run app that consumes messages
        runApplication(config, MyBlockingProcessor.class);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> received.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < COUNT; i++) {
            publisher.publish(String.valueOf(i + 1), tp);
        }

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);
    }

    @ApplicationScoped
    static class MyBlockingProcessor {
        @Incoming("in")
        @Outgoing("out")
        @Blocking(ordered = false)
        Message<String> in(SolaceInboundMessage<byte[]> msg) {
            return Message.of(msg.getMessage().getPayloadAsString()).withAck(() -> {
                msg.ack();
                return CompletableFuture.completedFuture(null);
            });
        }
    }
}
