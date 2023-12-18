package io.quarkiverse.solace.converters;

import java.lang.reflect.Type;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Message;

import com.solace.messaging.receiver.InboundMessage;

import io.quarkiverse.solace.incoming.SolaceInboundMetadata;
import io.smallrye.reactive.messaging.MessageConverter;
import io.smallrye.reactive.messaging.providers.helpers.TypeUtils;

@ApplicationScoped
public class SolaceMessageConverter implements MessageConverter {
    @Override
    public boolean canConvert(Message<?> in, Type target) {
        return TypeUtils.isAssignable(target, InboundMessage.class)
                && in.getMetadata(SolaceInboundMetadata.class).isPresent();
    }

    @Override
    public Message<?> convert(Message<?> in, Type target) {
        return in.withPayload(in.getMetadata(SolaceInboundMetadata.class)
                .map(SolaceInboundMetadata::getMessage).orElse(null));
    }
}
