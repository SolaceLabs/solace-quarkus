package io.quarkiverse.solace.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.solace.messaging.MessagingService;
import com.solace.messaging.config.MissingResourcesCreationConfiguration;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.receiver.PersistentMessageReceiver;
import com.solace.messaging.resources.Queue;
import com.solace.messaging.resources.Topic;
import com.solace.messaging.resources.TopicSubscription;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.QuarkusTestResource;

/**
 * Based on
 * <a href=
 * "https://github.com/SolaceSamples/solace-samples-java/blob/main/src/main/java/com/solace/samples/java/HelloWorld.java">Hello
 * World</a>
 * but use persistent messaging.
 */
@QuarkusTestResource(SolaceTestResource.class)
public class SolaceHelloWorldPersistentTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(HelloWorldReceiver.class, HelloWorldPublisher.class));

    @Inject
    HelloWorldPublisher publisher;
    @Inject
    HelloWorldReceiver receiver;

    @Inject
    MessagingService service;

    @Test
    public void hello() {
        publisher.send("Hello World 1");
        publisher.send("Hello World 2");
        publisher.send("Hello World 3");

        await().until(() -> receiver.list().size() == 3);

        for (InboundMessage message : receiver.list()) {
            assertThat(message.getPayloadAsString()).startsWith("Hello World");
        }
    }

    @ApplicationScoped
    public static class HelloWorldReceiver {

        @Inject
        MessagingService messagingService;
        private PersistentMessageReceiver receiver;
        private final List<InboundMessage> list = new CopyOnWriteArrayList<>();

        public void init(@Observes StartupEvent ev) {
            receiver = messagingService.createPersistentMessageReceiverBuilder()
                    .withSubscriptions(TopicSubscription.of("hello/persistent"))
                    .withMissingResourcesCreationStrategy(
                            MissingResourcesCreationConfiguration.MissingResourcesCreationStrategy.CREATE_ON_START)
                    .build(Queue.durableExclusiveQueue("my-queue")).start();
            receiver.receiveAsync(m -> {
                receiver.ack(m);
                list.add(m);
            });
        }

        public List<InboundMessage> list() {
            return list;
        }

        public void stop(@Observes ShutdownEvent ev) {
            receiver.terminate(100);
        }
    }

    @ApplicationScoped
    public static class HelloWorldPublisher {
        @Inject
        MessagingService messagingService;
        private PersistentMessagePublisher publisher;

        public void init(@Observes StartupEvent ev) {
            publisher = messagingService.createPersistentMessagePublisherBuilder()
                    .onBackPressureWait(1)
                    .build().start();
        }

        public void send(String message) {
            String topicString = "hello/persistent";
            OutboundMessage om = messagingService.messageBuilder().build(message);
            try {
                publisher.publishAwaitAcknowledgement(om, Topic.of(topicString), 10000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void stop(@Observes ShutdownEvent ev) {
            publisher.terminate(100);
        }
    }
}
