package com.solace.quarkus.samples;

import java.nio.charset.StandardCharsets;
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
     * Publishes message to topic hello/foobar which is subscribed by queue.foobar
     *
     * @see #consumeMessage(SolaceInboundMessage)
     * @return
     */
    @Outgoing("hello-out")
    Multi<Message<String>> publishMessage() {
        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .setApplicationMessageId("1").createPubSubOutboundMetadata();
        return Multi.createFrom().items("1").map(m -> Message.of(m, Metadata.of(outboundMetadata)));
    }

    /**
     * Receives message from queue - queue.foobar
     *
     * @param p
     */
    @Incoming("hello-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    CompletionStage<Void> consumeMessage(SolaceInboundMessage<?> p) {
        Log.infof("Received message: %s from topic: %s", new String(p.getMessage().getPayloadAsBytes(), StandardCharsets.UTF_8),
                p.getMessage().getDestinationName());
        return p.ack();
    }

    /**
     * Receives message from queue - queue.dynamic.topic and overwrites the topic configured in outgoing channel
     * dynamic-destination-out
     *
     * See [resources/application.properties#mp.messaging.outgoing.dynamic-destination-out.producer.topic]
     *
     * @param p
     */
    @Incoming("dynamic-destination-in")
    @Outgoing("dynamic-destination-out")
    Message<?> consumeAndPublishToDynamicTopic(SolaceInboundMessage<?> p) {
        Log.infof("Received message: %s from topic: %s", new String(p.getMessage().getPayloadAsBytes(), StandardCharsets.UTF_8),
                p.getMessage().getDestinationName());
        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .setApplicationMessageId("test")
                .setDynamicDestination("hello/foobar/" + p.getMessage().getApplicationMessageId())
                .createPubSubOutboundMetadata();
        return p.addMetadata(outboundMetadata);
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
