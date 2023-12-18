package io.quarkiverse.solace.locals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import com.solace.messaging.publisher.PersistentMessagePublisher;
import com.solace.messaging.resources.Topic;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.quarkiverse.solace.SolaceConnector;
import io.quarkiverse.solace.base.WeldTestBase;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.providers.locals.LocalContextMetadata;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

public class LocalPropagationAckTest extends WeldTestBase {

    private MapBasedConfig dataconfig() {
        return new MapBasedConfig()
                .with("mp.messaging.incoming.data.connector", SolaceConnector.CONNECTOR_NAME)
                .with("mp.messaging.incoming.data.consumer.queue.subscriptions", topic)
                .with("mp.messaging.incoming.data.consumer.queue.add-additional-subscriptions", "true")
                .with("mp.messaging.incoming.data.consumer.queue.missing-resource-creation-strategy", "create-on-start")
                .with("mp.messaging.incoming.data.consumer.queue.name", topic);
    }

    private void sendMessages() {
        PersistentMessagePublisher publisher = messagingService.createPersistentMessagePublisherBuilder()
                .build()
                .start();
        Topic tp = Topic.of(topic);
        for (int i = 0; i < 5; i++) {
            publisher.publish(messagingService.messageBuilder()
                    .withHTTPContentHeader(HttpHeaderValues.TEXT_PLAIN.toString(), "")
                    .build(String.valueOf(i + 1)),
                    tp);
        }
    }

    @Test
    public void testChannelWithAckOnMessageContext() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig(),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> i + 1);
        sendMessages();

        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).containsExactly(2, 3, 4, 5, 6);
    }

    @Test
    public void testIncomingChannelWithNackOnMessageContextFailStop() {
        IncomingChannelWithAckOnMessageContext bean = runApplication(dataconfig(),
                IncomingChannelWithAckOnMessageContext.class);
        bean.process(i -> {
            throw new RuntimeException("boom");
        });
        sendMessages();

        await().until(() -> bean.getResults().size() >= 5);
        assertThat(bean.getResults()).contains(1, 2, 3, 4, 5);
    }

    @ApplicationScoped
    public static class IncomingChannelWithAckOnMessageContext {

        private final List<Integer> list = new CopyOnWriteArrayList<>();

        @Inject
        @Channel("data")
        Multi<Message<String>> incoming;

        void process(Function<Integer, Integer> mapper) {
            incoming.map(m -> m.withPayload(Integer.parseInt(m.getPayload())))
                    .onItem()
                    .transformToUniAndConcatenate(msg -> Uni.createFrom()
                            .item(() -> msg.withPayload(mapper.apply(msg.getPayload())))
                            .chain(m -> Uni.createFrom().completionStage(m.ack()).replaceWith(m))
                            .onFailure().recoverWithUni(t -> Uni.createFrom().completionStage(msg.nack(t))
                                    .onItemOrFailure().transform((unused, throwable) -> msg)))
                    .subscribe().with(m -> {
                        System.out.println(m + " " + m.getMetadata(LocalContextMetadata.class));
                        m.getMetadata(LocalContextMetadata.class).map(LocalContextMetadata::context).ifPresent(context -> {
                            list.add(m.getPayload());
                        });
                    });
        }

        List<Integer> getResults() {
            return list;
        }
    }

}
