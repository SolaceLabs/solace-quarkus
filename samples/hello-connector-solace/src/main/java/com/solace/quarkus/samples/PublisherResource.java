package com.solace.quarkus.samples;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.quarkus.messaging.outgoing.SolaceOutboundMetadata;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;

@Path("/hello")
public class PublisherResource {

    @Channel("publisher-out")
    MutinyEmitter<Person> foobar;

    /**
     * Publishes to static topic configured in application.properties
     *
     * @param person
     * @return
     */
    @POST
    @Path("/publish")
    public Uni<Void> publish(Person person) {
        return foobar.send(person);
    }

    /**
     * Publishes to dynamic topic test/topic/<name-field-in-person-object and also add additional headers on message(ex:
     * ApplicationMessageID)
     *
     * @param person
     * @return
     */
    @POST
    @Path("/dynamictopic")
    public Uni<Void> publishToDynamicTopic(Person person) {

        SolaceOutboundMetadata outboundMetadata = SolaceOutboundMetadata.builder()
                .setApplicationMessageId("test").setDynamicDestination("test/topic/" + person.name)
                .createPubSubOutboundMetadata();
        Message<Person> personMessage = Message.of(person, Metadata.of(outboundMetadata));
        return foobar.sendMessage(personMessage);
    }

}
