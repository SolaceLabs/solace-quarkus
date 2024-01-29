package com.solace.quarkus.messaging.incoming;

import com.solace.messaging.config.MessageAcknowledgementConfiguration;

public class SettleMetadata {

    MessageAcknowledgementConfiguration.Outcome settleOutcome;

    public static SettleMetadata accepted() {
        return new SettleMetadata(MessageAcknowledgementConfiguration.Outcome.ACCEPTED);
    }

    public static SettleMetadata rejected() {
        return new SettleMetadata(MessageAcknowledgementConfiguration.Outcome.REJECTED);
    }

    public static SettleMetadata failed() {
        return new SettleMetadata(MessageAcknowledgementConfiguration.Outcome.FAILED);
    }

    public SettleMetadata(MessageAcknowledgementConfiguration.Outcome settleOutcome) {
        this.settleOutcome = settleOutcome;
    }

    public MessageAcknowledgementConfiguration.Outcome getOutcome() {
        return settleOutcome;
    }
}
