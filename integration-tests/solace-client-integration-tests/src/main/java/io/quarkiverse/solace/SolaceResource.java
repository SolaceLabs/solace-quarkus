package io.quarkiverse.solace;

import java.util.List;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import com.solace.messaging.MessagingService;
import com.solace.messaging.publisher.DirectMessagePublisher;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;

import io.quarkus.runtime.StartupEvent;

@Path("/solace")
public class SolaceResource {

    @Inject
    SolaceConsumer consumer;

    @Inject
    MessagingService solace;
    private DirectMessagePublisher directMessagePublisher;
    private PersistentMessagePublisher persistentMessagePublisher;

    public void init(@Observes StartupEvent ev) {
        directMessagePublisher = solace.createDirectMessagePublisherBuilder().build().start();
        persistentMessagePublisher = solace.createPersistentMessagePublisherBuilder().build().start();
    }

    @GET
    @Path("/direct")
    @Produces("application/json")
    public List<String> getDirectMessages() {
        return consumer.direct();
    }

    @GET
    @Path("/persistent")
    @Produces("application/json")
    public List<String> getPersistentMessages() {
        return consumer.persistent();
    }

    @POST
    @Path("/direct")
    public void sendDirect(String message) {
        directMessagePublisher.publish(message, Topic.of("hello/direct"));
    }

    @POST
    @Path("/persistent")
    public void sendPersistent(String message) {
        persistentMessagePublisher.publish(message, Topic.of("hello/persistent"));
    }

}
