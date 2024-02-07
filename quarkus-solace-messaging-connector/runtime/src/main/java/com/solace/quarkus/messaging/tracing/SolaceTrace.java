package com.solace.quarkus.messaging.tracing;

import java.util.Map;

public class SolaceTrace {
    private final String destinationKind;
    private final String topic;
    private final String messageId;
    private final String correlationId;
    private final String partitionKey;
    private final Long payloadSize;
    private final Map<String, String> messageProperties;

    private SolaceTrace(String destinationKind, String topic, String messageId, String correlationId, String partitionKey,
            Long payloadSize, Map<String, String> messageProperties) {
        this.destinationKind = destinationKind;
        this.topic = topic;
        this.messageId = messageId;
        this.correlationId = correlationId;
        this.partitionKey = partitionKey;
        this.payloadSize = payloadSize;
        this.messageProperties = messageProperties;
    }

    public String getDestinationKind() {
        return destinationKind;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public Long getPayloadSize() {
        return payloadSize;
    }

    public Map<String, String> getMessageProperties() {
        return messageProperties;
    }

    public static class Builder {
        private String destinationKind;
        private String topic;
        private String messageId;
        private String correlationId;
        private String partitionKey;
        private Long payloadSize;
        private Map<String, String> properties;

        public Builder withDestinationKind(String destinationKind) {
            this.destinationKind = destinationKind;
            return this;
        }

        public Builder withTopic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder withMessageID(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder withCorrelationID(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder withPartitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        public Builder withPayloadSize(Long payloadSize) {
            this.payloadSize = payloadSize;
            return this;
        }

        public Builder withProperties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public SolaceTrace build() {
            return new SolaceTrace(destinationKind, topic, messageId, correlationId, partitionKey, payloadSize, properties);
        }
    }
}
