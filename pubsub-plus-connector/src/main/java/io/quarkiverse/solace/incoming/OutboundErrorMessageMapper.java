package io.quarkiverse.solace.incoming;

import java.util.Properties;

import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.receiver.InboundMessage;

import io.quarkiverse.solace.SolaceConnectorIncomingConfiguration;

class OutboundErrorMessageMapper {

    public OutboundMessage mapError(OutboundMessageBuilder messageBuilder, InboundMessage inputMessage,
            SolaceConnectorIncomingConfiguration incomingConfiguration) {
        Properties extendedMessageProperties = new Properties();

        extendedMessageProperties.setProperty(SolaceProperties.MessageProperties.PERSISTENT_DMQ_ELIGIBLE,
                Boolean.toString(incomingConfiguration.getConsumerQueueErrorMessageDmqEligible().booleanValue()));
        messageBuilder.fromProperties(extendedMessageProperties);

        incomingConfiguration.getConsumerQueueErrorMessageTtl().ifPresent(ttl -> {
            messageBuilder.withTimeToLive(incomingConfiguration.getConsumerQueueErrorMessageTtl().get());
        });

        return messageBuilder.build(inputMessage.getPayloadAsBytes());
    }
}
