package io.quarkiverse.solace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.Test;

import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.Topic;
import com.solace.messaging.resources.TopicSubscription;

import io.quarkiverse.solace.base.WeldTestBase;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class SolaceProcessorTest extends WeldTestBase {

    @Test
    void consumer() {
        String processedTopic = topic + "-processed";
        MapBasedConfig config = new MapBasedConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.queue.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.producer.topic", processedTopic);

        // Run app that processes messages
        MyProcessor app = runApplication(config, MyProcessor.class);

        List<String> expected = new CopyOnWriteArrayList<>();

        // Start listening processed messages
        PersistentMessageReceiver receiver = messagingService.createPersistentMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of(processedTopic))
                .build(Queue.nonDurableExclusiveQueue());
        receiver.receiveAsync(inboundMessage -> expected.add(inboundMessage.getPayloadAsString()));
        receiver.start();

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        publisher.publish("1", tp);
        publisher.publish("2", tp);
        publisher.publish("3", tp);
        publisher.publish("4", tp);
        publisher.publish("5", tp);

        // Assert on received messages
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));
        // Assert on processed messages
        await().untilAsserted(() -> assertThat(expected).contains("1", "2", "3", "4", "5"));
    }

    @ApplicationScoped
    static class MyProcessor {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        @Outgoing("out")
        OutboundMessage in(InboundMessage msg) {
            String payload = msg.getPayloadAsString();
            received.add(payload);
            return messagingService.messageBuilder().build(payload);
        }

        public List<String> getReceived() {
            return received;
        }
    }
}
