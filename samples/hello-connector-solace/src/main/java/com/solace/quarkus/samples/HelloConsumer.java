package com.solace.quarkus.samples;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.*;

import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solace.quarkus.messaging.outgoing.SolaceOutboundMetadata;

import io.quarkus.logging.Log;

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

}
