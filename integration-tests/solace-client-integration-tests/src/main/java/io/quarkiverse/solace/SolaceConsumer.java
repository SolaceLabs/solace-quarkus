package io.quarkiverse.solace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MissingResourcesCreationConfiguration;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.TopicSubscription;

import io.quarkus.runtime.ShutdownEvent;

@ApplicationScoped
public class SolaceConsumer {

    private final DirectMessageReceiver directReceiver;
    private final PersistentMessageReceiver persistentReceiver;
    List<String> direct = new CopyOnWriteArrayList<>();
    List<String> persistent = new CopyOnWriteArrayList<>();

    public SolaceConsumer(MessagingService solace) {
        directReceiver = solace.createDirectMessageReceiverBuilder()
                .withSubscriptions(TopicSubscription.of("hello/direct"))
                .build().start();
        persistentReceiver = solace.createPersistentMessageReceiverBuilder()
                .withMissingResourcesCreationStrategy(
                        MissingResourcesCreationConfiguration.MissingResourcesCreationStrategy.CREATE_ON_START)
                .withSubscriptions(TopicSubscription.of("hello/persistent"))
                .build(Queue.durableExclusiveQueue("hello/persistent")).start();

        directReceiver.receiveAsync(h -> consumeDirect(h.getPayloadAsString()));
        persistentReceiver.receiveAsync(h -> consumePersistent(h.getPayloadAsString()));
    }

    public void shutdown(@Observes ShutdownEvent event) {
        directReceiver.terminate(1);
        persistentReceiver.terminate(1);
    }

    public void consumeDirect(String message) {
        direct.add(message);
    }

    public void consumePersistent(String message) {
        persistent.add(message);
    }

    public List<String> direct() {
        return direct;
    }

    public List<String> persistent() {
        return persistent;
    }
}
