package com.solace.quarkus.samples;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.*;

import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solace.quarkus.messaging.outgoing.SolaceOutboundMetadata;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
public class HelloConsumer {
    /**
     * Publish a simple message using TryMe in Solace broker and you should see the message published to topic
     *
     * @param p
     */
    @Incoming("hello-in")
    @Outgoing("hello-out")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    Message<?> consumeAndPublish(SolaceInboundMessage<?> p) {
        Log.infof("Received message: %s", new String(p.getMessage().getPayloadAsBytes(), StandardCharsets.UTF_8));
        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .createPubSubOutboundMetadata();
        Message<?> outboundMessage = Message.of(p.getPayload(), Metadata.of(outboundMetadata), () -> {
            CompletableFuture completableFuture = new CompletableFuture();
            p.ack();
            completableFuture.complete(null);
            return completableFuture;
        }, (throwable) -> {
            CompletableFuture completableFuture = new CompletableFuture();
            p.nack(throwable, p.getMetadata());
            completableFuture.complete(null);
            return completableFuture;
        });
        return outboundMessage;
    }

    /**
     * Publish a simple string from using TryMe in Solace broker and you should see the message published to dynamic destination
     * topic
     *
     * @param p
     */
    @Incoming("dynamic-destination-in")
    @Outgoing("dynamic-destination-out")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    Message<?> consumeAndPublishToDynamicTopic(SolaceInboundMessage<?> p) {
        Log.infof("Received message: %s", new String(p.getMessage().getPayloadAsBytes(), StandardCharsets.UTF_8));
        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .setApplicationMessageId("test")
                .setDynamicDestination("solace/quarkus/producer/" + p.getMessage().getCorrelationId()) // make sure correlationID is available on incoming message
                .createPubSubOutboundMetadata();
        Message<?> outboundMessage = Message.of(p.getPayload(), Metadata.of(outboundMetadata), () -> {
            CompletableFuture completableFuture = new CompletableFuture();
            p.ack();
            completableFuture.complete(null);
            return completableFuture;
        }, (throwable) -> {
            CompletableFuture completableFuture = new CompletableFuture();
            p.nack(throwable, p.getMetadata());
            completableFuture.complete(null);
            return completableFuture;
        });
        return outboundMessage;
    }

    @Incoming("partition-consumer-1-in")
    CompletionStage<Void> partitionConsumer1(SolaceInboundMessage<?> message) {
        Log.infof(
                "Received message on partitionConsumer1 with partition key %s and payload %s",
                message.getMessage().getProperties().get("JMSXGroupID"), message.getMessage().getPayloadAsString());

        return message.ack();
    }

    @Incoming("partition-consumer-2-in")
    CompletionStage<Void> partitionConsumer2(SolaceInboundMessage<?> message) {
        Log.infof(
                "Received message on partitionConsumer2 with partition key %s and payload %s",
                message.getMessage().getProperties().get("JMSXGroupID"), message.getMessage().getPayloadAsString());
        return message.ack();
    }

    @Outgoing("partition-publisher-out")
    public Multi<Message<String>> partitionPublisher() {

        return Multi.createFrom().range(0, 1000).map(mapper -> {
            String partitionKey = "2";
            if (mapper % 2 == 0) {
                partitionKey = "1";
            } else if (mapper % 3 == 0) {
                partitionKey = "3";
            }
            SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                    .setPartitionKey(partitionKey)
                    .createPubSubOutboundMetadata();
            return Message.of("Hello World - " + mapper, Metadata.of(outboundMetadata),
                    () -> CompletableFuture.completedFuture(null));
        });
    }

}
