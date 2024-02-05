package com.solace.quarkus.messaging.perf;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.*;
import org.junit.jupiter.api.Test;

import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.base.WeldTestBase;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolacePublisherPerformanceTest extends WeldTestBase {

    private static final int COUNT = 100000;
    private static final int TIMEOUT_IN_SECONDS = 400;

    @Test
    void publisherPerformanceTestWithBackPressureWaitAndWaitForPublishReceipt() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            received.add(inboundMessage.getPayloadAsString());
        });
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        long start = System.currentTimeMillis();
        //        app.run();
        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> app.getCount() == COUNT);
        long end = System.currentTimeMillis();

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);

        System.out.println("Total time : " + (end - start) + " ms");
        long duration = end - start;
        double speed = (COUNT * 1.0) / (duration / 1000.0);
        System.out.println(speed + " messages/ms");
    }

    @Test
    void publisherPerformanceTestWithBackPressureWaitAndNoWaitForPublishReceipt() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic)
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            received.add(inboundMessage.getPayloadAsString());
        });
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        long start = System.currentTimeMillis();
        //        app.run();
        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> app.getCount() == COUNT);
        long end = System.currentTimeMillis();

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);

        System.out.println("Total time : " + (end - start) + " ms");
        long duration = end - start;
        double speed = (COUNT * 1.0) / (duration / 1000.0);
        System.out.println(speed + " messages/ms");
    }

    @Test
    void publisherPerformanceTestWithBackPressureElasticAndWaitForPublishReceipt() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic");

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            received.add(inboundMessage.getPayloadAsString());
        });
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        long start = System.currentTimeMillis();
        //        app.run();
        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> app.getCount() == COUNT);
        long end = System.currentTimeMillis();

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);

        System.out.println("Total time : " + (end - start) + " ms");
        long duration = end - start;
        double speed = (COUNT * 1.0) / (duration / 1000.0);
        System.out.println(speed + " messages/ms");
    }

    @Test
    void publisherPerformanceTestWithBackPressureElasticAndNoWaitForPublishReceipt() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.strategy", "elastic")
                .with("mp.messaging.outgoing.out.producer.waitForPublishReceipt", false);

        List<String> received = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withMessageAutoAcknowledgement()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            received.add(inboundMessage.getPayloadAsString());
        });
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        long start = System.currentTimeMillis();
        //        app.run();
        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> app.getCount() == COUNT);
        long end = System.currentTimeMillis();

        await()
                .atMost(Duration.ofSeconds(TIMEOUT_IN_SECONDS))
                .until(() -> received.size() == COUNT);

        System.out.println("Total time : " + (end - start) + " ms");
        long duration = end - start;
        double speed = (COUNT * 1.0) / (duration / 1000.0);
        System.out.println(speed + " messages/ms");
    }

    @ApplicationScoped
    static class MyApp {
        LongAdder count = new LongAdder();

        @Outgoing("out")
        Multi<Message<Integer>> out() {

            return Multi.createFrom().range(0, COUNT)
                    .map(payload -> Message.of(payload).withAck(() -> {
                        count.increment();
                        return CompletableFuture.completedFuture(null);
                    }));
        }

        public long getCount() {
            return count.longValue();
        }
    }
}
