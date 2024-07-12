package com.solace.quarkus.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.junit.jupiter.api.Test;

import com.solace.messaging.publisher.DirectMessagePublisher;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.resources.Topic;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;

import io.smallrye.reactive.messaging.health.HealthReport;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolaceDirectMessageConsumerHealthTest extends WeldTestBase {

    @Test
    void solaceConsumerHealthCheck() {
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic);

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        await().until(() -> isStarted() && isReady());

        // Produce messages
        DirectMessagePublisher publisher = messagingService.createDirectMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        publisher.publish("1", tp);
        publisher.publish("2", tp);
        publisher.publish("3", tp);
        publisher.publish("4", tp);
        publisher.publish("5", tp);

        await().until(() -> isAlive());

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
    void solaceConsumerLivenessCheck() {
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.client.type", "direct")
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic);

        // Run app that consumes messages
        MyErrorConsumer app = runApplication(config, MyErrorConsumer.class);

        await().until(() -> isStarted() && isReady());

        // Produce messages
        DirectMessagePublisher publisher = messagingService.createDirectMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        publisher.publish("1", tp);
        publisher.publish("2", tp);

        await().until(() -> isAlive());

        HealthReport startup = getHealth().getStartup();
        HealthReport liveness = getHealth().getLiveness();
        HealthReport readiness = getHealth().getReadiness();

        assertThat(startup.isOk()).isTrue();
        assertThat(liveness.isOk()).isTrue();
        assertThat(readiness.isOk()).isTrue();
        assertThat(startup.getChannels()).hasSize(1);
        assertThat(liveness.getChannels()).hasSize(1);
        assertThat(readiness.getChannels()).hasSize(1);

        publisher.publish("3", tp);
        await().until(() -> {
            HealthReport healthReport = getHealth().getLiveness();
            return (healthReport.isOk() == false && !healthReport.getChannels().get(0).getMessage().isEmpty());
        });

        publisher.publish("4", tp);
        publisher.publish("5", tp);
        await().until(() -> getHealth().getLiveness().isOk() == true);
    }

    @ApplicationScoped
    static class MyConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        void in(InboundMessage msg) {
            received.add(msg.getPayloadAsString());
        }

        public List<String> getReceived() {
            return received;
        }
    }

    @ApplicationScoped
    static class MyErrorConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        CompletionStage<Void> in(SolaceInboundMessage<byte[]> msg) {
            String payload = new String(msg.getPayload(), StandardCharsets.UTF_8);
            if (payload.equals("3")) {
                return msg.nack(new IllegalArgumentException("Nacking message with payload 3"));
            }

            return msg.ack();
        }
    }
}
