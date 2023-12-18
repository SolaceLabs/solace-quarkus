package io.quarkiverse.solace.outgoing;

import java.util.Map;

public class SolaceOutboundMetadata {

    private final Map<String, String> httpContentHeaders;
    private final Long expiration;
    private final Integer priority;
    private final String senderId;
    private final Map<String, String> properties;
    private final String applicationMessageType;
    private final Long timeToLive;
    private final String applicationMessageId;
    private final Integer classOfService;
    private final String dynamicDestination;

    public static PubSubOutboundMetadataBuilder builder() {
        return new PubSubOutboundMetadataBuilder();
    }

    public SolaceOutboundMetadata(Map<String, String> httpContentHeaders,
            Long expiration,
            Integer priority,
            String senderId,
            Map<String, String> properties,
            String applicationMessageType,
            Long timeToLive,
            String applicationMessageId,
            Integer classOfService, String dynamicDestination) {
        this.httpContentHeaders = httpContentHeaders;
        this.expiration = expiration;
        this.priority = priority;
        this.senderId = senderId;
        this.properties = properties;
        this.applicationMessageType = applicationMessageType;
        this.timeToLive = timeToLive;
        this.applicationMessageId = applicationMessageId;
        this.classOfService = classOfService;
        this.dynamicDestination = dynamicDestination;
    }

    public Map<String, String> getHttpContentHeaders() {
        return httpContentHeaders;
    }

    public Long getExpiration() {
        return expiration;
    }

    public Integer getPriority() {
        return priority;
    }

    public String getSenderId() {
        return senderId;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public String getApplicationMessageType() {
        return applicationMessageType;
    }

    public Long getTimeToLive() {
        return timeToLive;
    }

    public String getApplicationMessageId() {
        return applicationMessageId;
    }

    public Integer getClassOfService() {
        return classOfService;
    }

    public String getDynamicDestination() {
        return dynamicDestination;
    }

    public static class PubSubOutboundMetadataBuilder {
        private Map<String, String> httpContentHeaders;
        private Long expiration;
        private Integer priority;
        private String senderId;
        private Map<String, String> properties;
        private String applicationMessageType;
        private Long timeToLive;
        private String applicationMessageId;
        private Integer classOfService;
        private String dynamicDestination;

        public PubSubOutboundMetadataBuilder setHttpContentHeaders(Map<String, String> httpContentHeader) {
            this.httpContentHeaders = httpContentHeaders;
            return this;
        }

        public PubSubOutboundMetadataBuilder setExpiration(Long expiration) {
            this.expiration = expiration;
            return this;
        }

        public PubSubOutboundMetadataBuilder setPriority(Integer priority) {
            this.priority = priority;
            return this;
        }

        public PubSubOutboundMetadataBuilder setSenderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public PubSubOutboundMetadataBuilder setProperties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public PubSubOutboundMetadataBuilder setApplicationMessageType(String applicationMessageType) {
            this.applicationMessageType = applicationMessageType;
            return this;
        }

        public PubSubOutboundMetadataBuilder setTimeToLive(Long timeToLive) {
            this.timeToLive = timeToLive;
            return this;
        }

        public PubSubOutboundMetadataBuilder setApplicationMessageId(String applicationMessageId) {
            this.applicationMessageId = applicationMessageId;
            return this;
        }

        public PubSubOutboundMetadataBuilder setClassOfService(Integer classOfService) {
            this.classOfService = classOfService;
            return this;
        }

        public PubSubOutboundMetadataBuilder setDynamicDestination(String dynamicDestination) {
            this.dynamicDestination = dynamicDestination;
            return this;
        }

        public SolaceOutboundMetadata createPubSubOutboundMetadata() {
            return new SolaceOutboundMetadata(httpContentHeaders, expiration, priority, senderId, properties,
                    applicationMessageType, timeToLive, applicationMessageId, classOfService, dynamicDestination);
        }
    }
}
