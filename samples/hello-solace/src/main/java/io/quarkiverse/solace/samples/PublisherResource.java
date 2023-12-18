package io.quarkiverse.solace.samples;

import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.solace.messaging.MessagingService;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;

import io.quarkus.runtime.ShutdownEvent;

@Path("/hello")
public class PublisherResource {

    private final PersistentMessagePublisher publisher;

    public PublisherResource(MessagingService solace) {
        publisher = solace.createPersistentMessagePublisherBuilder()
                .onBackPressureWait(1)
                .build().start();
    }

    @POST
    public void publish(String message) {
        String topicString = "hello/foobar";
        publisher.publish(message, Topic.of(topicString));
    }

    public void stop(@Observes ShutdownEvent ev) {
        publisher.terminate(1);
    }
}
