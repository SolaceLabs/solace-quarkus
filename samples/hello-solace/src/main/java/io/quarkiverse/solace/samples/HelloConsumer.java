package io.quarkiverse.solace.samples;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.solace.messaging.MessagingService;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.resources.TopicSubscription;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class HelloConsumer {

    @Inject
    MessagingService solace;

    private DirectMessageReceiver receiver;

    public void init(@Observes StartupEvent ev) {
        receiver = solace.createDirectMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of("hello/foobar")).build().start();
        receiver.receiveAsync(m -> {
            Log.infof("Received message: %s", m.getPayloadAsString());
        });
    }

    public void stop(@Observes ShutdownEvent ev) {
        receiver.terminate(1);
    }
}
