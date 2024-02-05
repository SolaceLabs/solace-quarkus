package com.solace.quarkus.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.Test;

import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.base.WeldTestBase;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolacePublisherHealthTest extends WeldTestBase {
    @Test
    void publisherHealthCheck() {
        MapBasedConfig config = new MapBasedConfig()
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

        await().until(() -> isStarted() && isReady() && isAlive());

        HealthReport startup = getHealth().getStartup();
        HealthReport liveness = getHealth().getLiveness();
        HealthReport readiness = getHealth().getReadiness();

        assertThat(startup.isOk()).isTrue();
        assertThat(liveness.isOk()).isTrue();
        assertThat(readiness.isOk()).isTrue();
        assertThat(startup.getChannels()).hasSize(1);
        assertThat(liveness.getChannels()).hasSize(1);
        assertThat(readiness.getChannels()).hasSize(1);
    }

    @Test
    void publisherLivenessCheck() {
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", "publish/deny");

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening first
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of("publish/deny"))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> expected.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Run app that publish messages
        MyApp app = runApplication(config, MyApp.class);

        await().until(() -> isStarted() && isReady() && !isAlive());

        await().until(() -> !isAlive());

        HealthReport startup = getHealth().getStartup();
        HealthReport liveness = getHealth().getLiveness();
        HealthReport readiness = getHealth().getReadiness();

        assertThat(startup.isOk()).isTrue();
        assertThat(liveness.isOk()).isFalse();
        assertThat(readiness.isOk()).isTrue();
        assertThat(startup.getChannels()).hasSize(1);
        assertThat(liveness.getChannels()).hasSize(1);
        assertThat(readiness.getChannels()).hasSize(1);
        assertThat(liveness.getChannels().get(0).getMessage()).isNotEmpty();
    }

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
}
