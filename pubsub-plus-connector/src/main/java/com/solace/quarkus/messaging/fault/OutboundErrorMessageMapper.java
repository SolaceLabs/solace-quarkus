package com.solace.quarkus.messaging.fault;

import java.util.Properties;

import com.solace.messaging.config.SolaceProperties;
import com.solace.messaging.publisher.OutboundMessage;
import com.solace.messaging.publisher.OutboundMessageBuilder;
import com.solace.messaging.receiver.InboundMessage;

class OutboundErrorMessageMapper {

    public OutboundMessage mapError(OutboundMessageBuilder messageBuilder, InboundMessage inputMessage,
            boolean dmqEligible, Long timeToLive) {
        Properties extendedMessageProperties = new Properties();

        extendedMessageProperties.setProperty(SolaceProperties.MessageProperties.PERSISTENT_DMQ_ELIGIBLE,
                Boolean.toString(dmqEligible));
        messageBuilder.fromProperties(extendedMessageProperties);
        if (timeToLive != null) {
            messageBuilder.withTimeToLive(timeToLive);
        }

        return messageBuilder.build(inputMessage.getPayloadAsBytes());
    }
}
