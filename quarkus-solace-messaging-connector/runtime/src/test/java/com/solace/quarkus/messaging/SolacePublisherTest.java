package com.solace.quarkus.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.Test;

import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.base.UnsatisfiedInstance;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.outgoing.SolaceOutboundMetadata;
import com.solace.quarkus.messaging.outgoing.SolaceOutgoingChannel;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;
import io.vertx.mutiny.core.Vertx;

public class SolacePublisherTest extends WeldTestBase {

    @Test
    void publisher() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> expected.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getAcked()).contains("1", "2", "3", "4", "5"));
        // Assert on received messages
        await().untilAsserted(() -> assertThat(expected).contains("1", "2", "3", "4", "5"));
    }

    @Test
    void publisherWithDynamicDestination() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of("quarkus/integration/test/dynamic/topic/*"))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            expected.add(inboundMessage.getDestinationName());
        });
        receiver.start();

        // Run app that publish messages
        MyDynamicDestinationApp app = runApplication(config, MyDynamicDestinationApp.class);
        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getAcked()).contains("1", "2", "3", "4", "5"));
        // Assert on received messages
        await().untilAsserted(() -> assertThat(expected).contains("quarkus/integration/test/dynamic/topic/1",
                "quarkus/integration/test/dynamic/topic/2", "quarkus/integration/test/dynamic/topic/3",
                "quarkus/integration/test/dynamic/topic/4", "quarkus/integration/test/dynamic/topic/5"));
    }

    @Test
    void publisherWithBackPressureReject() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", topic)
                .with("mp.messaging.outgoing.out.producer.back-pressure.buffer-capacity", 1);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of("topic"))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> {
            expected.add(inboundMessage.getPayloadAsString());
        });
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);
        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getAcked().size()).isLessThan(5));
    }

    @Test
    void publisherGracefulCloseTest() {
        MapBasedConfig config = new MapBasedConfig()
                .with("channel-name", "out")
                .with("producer.topic", topic);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of(topic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> expected.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        SolaceOutgoingChannel solaceOutgoingChannel = new SolaceOutgoingChannel(Vertx.vertx(), UnsatisfiedInstance.instance(),
                new SolaceConnectorOutgoingConfiguration(config), messagingService);
        // Publish messages
        Multi.createFrom().range(0, 10)
                .map(Message::of)
                .subscribe((Flow.Subscriber<? super Message<Integer>>) solaceOutgoingChannel.getSubscriber());

        solaceOutgoingChannel.close();
        // Assert on received messages
        await().untilAsserted(() -> assertThat(expected.size()).isEqualTo(10));

    }

    //    @Test
    //    void publisherWithBackPressureRejectWaitForPublisherReadiness() {
    //        MapBasedConfig config = new MapBasedConfig()
    //                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
    //                .with("mp.messaging.outgoing.out.producer.topic", topic)
    //                .with("mp.messaging.outgoing.out.producer.back-pressure.buffer-capacity", 1);
    //
    //        List<String> expected = new CopyOnWriteArrayList<>();
    //
    //        // Start listening first
    //        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
    //                .withSubscriptions(TopicSubscription.of("topic"))
    //                .build(Queue.nonDurableExclusiveQueue());
    //        receiver.receiveAsync(inboundMessage -> {
    //            expected.add(inboundMessage.getPayloadAsString());
    //        });
    //        receiver.start();
    //
    //        // Run app that publish messages
    //        MyBackPressureRejectApp app = runApplication(config, MyBackPressureRejectApp.class);
    //        // Assert on published messages
    //        await().untilAsserted(() -> assertThat(app.getAcked()).contains("1", "2", "3", "4", "5"));
    //    }

    @ApplicationScoped
    static class MyApp {
        private final List<String> acked = new CopyOnWriteArrayList<>();

        @Outgoing("out")
        Multi<Message<String>> out() {

            return Multi.createFrom().items("1", "2", "3", "4", "5")
                    .map(payload -> Message.of(payload).withAck(() -> {
                        acked.add(payload);
                        return CompletableFuture.completedFuture(null);
                    }));
        }

        public List<String> getAcked() {
            return acked;
        }
    }

    //    @ApplicationScoped
    //    static class MyBackPressureRejectApp {
    //        private final List<String> acked = new CopyOnWriteArrayList<>();
    //        public boolean waitForPublisherReadiness = false;
    //        @Channel("outgoing")
    //        MutinyEmitter<String> foobar;
    //
    //        void out() {
    //            List<String> items = new ArrayList<>();
    //            items.add("1");
    //            items.add("2");
    //            items.add("3");
    //            items.add("4");
    //            items.add("5");
    //            items.forEach(payload -> {
    //                Message<String> message = Message.of(payload).withAck(() -> {
    //                    acked.add(payload);
    //                    return CompletableFuture.completedFuture(null);
    //                });
    //                if (waitForPublisherReadiness) {
    //                    while (SolaceOutgoingChannel.isPublisherReady) {
    //                        foobar.sendMessage(message);
    //                    }
    //                } else {
    //                    foobar.sendMessage(message);
    //                }
    //            });
    //        }
    //
    //        public List<String> getAcked() {
    //            return acked;
    //        }
    //    }

    @ApplicationScoped
    static class MyDynamicDestinationApp {
        private final List<String> acked = new CopyOnWriteArrayList<>();

        @Outgoing("out")
        Multi<Message<String>> out() {
            return Multi.createFrom().items("1", "2", "3", "4", "5")
                    .map(payload -> {
                        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                                .setApplicationMessageId("test")
                                .setDynamicDestination("quarkus/integration/test/dynamic/topic/" + payload)
                                .createPubSubOutboundMetadata();
                        Message<String> message = Message.of(payload, Metadata.of(outboundMetadata));
                        return message.withAck(() -> {
                            acked.add(payload);
                            return CompletableFuture.completedFuture(null);
                        });
                    });
        }

        public List<String> getAcked() {
            return acked;
        }
    }
}
