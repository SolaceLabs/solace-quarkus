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
import com.solace.messaging.publisher.DirectMessagePublisher;
import com.solace.messaging.receiver.DirectMessageReceiver;
import com.solace.messaging.receiver.InboundMessage;
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
 */
@QuarkusTestResource(SolaceTestResource.class)
public class SolaceHelloWorldTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(HelloWorldReceiver.class, HelloWorldPublisher.class));

    @Inject
    HelloWorldPublisher publisher;
    @Inject
    HelloWorldReceiver receiver;

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
        private DirectMessageReceiver receiver;
        private final List<InboundMessage> list = new CopyOnWriteArrayList<>();

        public void init(@Observes StartupEvent ev) {
            receiver = messagingService.createDirectMessageReceiverBuilder()
                    .withSubscriptions(TopicSubscription.of("hello/direct")).build().start();
            receiver.receiveAsync(list::add);
        }

        public List<InboundMessage> list() {
            return list;
        }

        public void stop(@Observes ShutdownEvent ev) {
            receiver.terminate(1);
        }
    }

    @ApplicationScoped
    public static class HelloWorldPublisher {
        @Inject
        MessagingService messagingService;
        private DirectMessagePublisher publisher;

        public void init(@Observes StartupEvent ev) {
            publisher = messagingService.createDirectMessagePublisherBuilder()
                    .onBackPressureWait(1).build().start();
        }

        public void send(String message) {
            String topicString = "hello/direct";
            publisher.publish(message, Topic.of(topicString));
        }

        public void stop(@Observes ShutdownEvent ev) {
            publisher.terminate(1);
        }
    }
}
