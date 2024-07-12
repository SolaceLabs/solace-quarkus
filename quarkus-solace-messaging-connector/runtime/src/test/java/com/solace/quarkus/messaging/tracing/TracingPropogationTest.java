package com.solace.quarkus.messaging.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.Topic;
import com.solace.messaging.resources.TopicSubscription;
import com.solace.quarkus.messaging.base.WeldTestBase;
import com.solace.quarkus.messaging.incoming.SolaceInboundMetadata;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class TracingPropogationTest extends WeldTestBase {
    private SdkTracerProvider tracerProvider;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    public void setup() {
        GlobalOpenTelemetry.resetForTest();

        spanExporter = InMemorySpanExporter.create();
        SpanProcessor spanProcessor = SimpleSpanProcessor.create(spanExporter);

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .setSampler(Sampler.alwaysOn())
                .build();

        OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }

    @AfterAll
    static void shutdown() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void consumer() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.client.tracing-enabled", "true")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", "quarkus/integration/test/replay/messages");

        // Run app that consumes messages
        MyConsumer app = runApplication(config, MyConsumer.class);

        // Produce messages
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of("quarkus/integration/test/replay/messages");
        publisher.publish("1", tp);
        publisher.publish("2", tp);
        publisher.publish("3", tp);
        publisher.publish("4", tp);
        publisher.publish("5", tp);

        // Assert on published messages
        await().untilAsserted(() -> assertThat(app.getReceived()).contains("1", "2", "3", "4", "5"));

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(5, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.CONSUMER, span.getKind());
        });
    }

    @Test
    void publisher() {
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.client.tracing-enabled", "true")
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

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(5, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.PRODUCER, span.getKind());
        });
    }

    @Test
    void processor() {
        String processedTopic = topic + "/processed";
        MapBasedConfig config = commonConfig()
                .with("mp.messaging.incoming.in.connector", "quarkus-solace")
                .with("mp.messaging.incoming.in.client.tracing-enabled", "true")
                .with("mp.messaging.incoming.in.consumer.queue.name", queue)
                .with("mp.messaging.incoming.in.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.in.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.in.consumer.subscriptions", topic)
                .with("mp.messaging.outgoing.out.connector", "quarkus-solace")
                .with("mp.messaging.outgoing.out.client.tracing-enabled", "true")
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

        CompletableResultCode completableResultCode = tracerProvider.forceFlush();
        completableResultCode.whenComplete(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertEquals(10, spans.size());

            assertEquals(5, spans.stream().map(SpanData::getTraceId).collect(Collectors.toSet()).size());

            SpanData span = spans.get(0);
            assertEquals(SpanKind.CONSUMER, span.getKind());

            span = spans.get(5);
            assertEquals(SpanKind.PRODUCER, span.getKind());
        });
    }

    @ApplicationScoped
    static class MyConsumer {
        private final List<String> received = new CopyOnWriteArrayList<>();

        @Incoming("in")
        CompletionStage<Void> in(Message<byte[]> msg) {
            SolaceInboundMetadata solaceInboundMetadata = msg.getMetadata(SolaceInboundMetadata.class).orElseThrow();
            received.add(solaceInboundMetadata.getPayloadAsString());
            return msg.ack();
        }

        public List<String> getReceived() {
            return received;
        }
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
