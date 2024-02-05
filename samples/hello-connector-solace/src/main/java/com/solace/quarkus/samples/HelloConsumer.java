package com.solace.quarkus.samples;

import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.*;

import com.solace.messaging.receiver.InboundMessage;
import com.solace.quarkus.messaging.incoming.SolaceInboundMetadata;
import com.solace.quarkus.messaging.outgoing.SolaceOutboundMetadata;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;

@ApplicationScoped
public class HelloConsumer {

    /**
     * Publishes message to topic hello/foobar which is subscribed by queue.foobar
     *
     * @see #consumeMessage(InboundMessage)
     * @return
     */
    @Outgoing("hello-out")
    Multi<Message<String>> publishMessage() {
        return Multi.createFrom().items("1", "2", "3", "4").map(m -> {
            SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                    .setApplicationMessageId(m).createPubSubOutboundMetadata();
            return Message.of(m, Metadata.of(outboundMetadata));
        });
    }

    /**
     * Receives message from queue - queue.foobar
     *
     * @param p
     */
    @Incoming("hello-in")
    void consumeMessage(InboundMessage p) {
        Log.infof("Received message: %s from topic: %s", p.getPayloadAsString(), p.getDestinationName());
    }

    /**
     * Receives message from queue - queue.foobar
     *
     * @param p
     */
    @Incoming("hello-plain-message-in")
    void consumePayload(String p) {
        Log.infof("Received message: %s", p);
    }

    /**
     * Receives message from queue - queue.foobar
     *
     * @param p
     */
    @Incoming("hello-reactive-message-in")
    CompletionStage<Void> consumeMessage(Message<String> p) {
        Log.infof("Received message: %s from topic: %s", p.getPayload(),
                p.getMetadata(SolaceInboundMetadata.class).get().getDestinationName());
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
    Message<?> consumeAndPublishToDynamicTopic(Message<?> p) {
        SolaceInboundMetadata metadata = p.getMetadata(SolaceInboundMetadata.class).get();
        Log.infof("Received message: %s from topic: %s", metadata.getPayloadAsString(), metadata.getDestinationName());
        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .setApplicationMessageId("test")
                .setDynamicDestination("hello/foobar/" + metadata.getApplicationMessageId())
                .createPubSubOutboundMetadata();
        return p.addMetadata(outboundMetadata);
    }

}
