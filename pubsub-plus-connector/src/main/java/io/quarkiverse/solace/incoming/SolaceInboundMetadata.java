package io.quarkiverse.solace.incoming;

import java.io.Serializable;
import java.util.Map;

import com.solace.messaging.PubSubPlusClientException;
import com.solace.messaging.receiver.InboundMessage;
import com.solace.messaging.util.Converter;
import com.solace.messaging.util.InteroperabilitySupport;

public class SolaceInboundMetadata {

    private final InboundMessage msg;

    public SolaceInboundMetadata(InboundMessage msg) {
        this.msg = msg;
    }

    public boolean isRedelivered() {
        return msg.isRedelivered();
    }

    public InboundMessage.MessageDiscardNotification getMessageDiscardNotification() {
        return msg.getMessageDiscardNotification();
    }

    public <T extends Serializable> T getAndConvertPayload(Converter.BytesToObject<T> bytesToObject, Class<T> aClass)
            throws PubSubPlusClientException.IncompatibleMessageException {
        return msg.getAndConvertPayload(bytesToObject, aClass);
    }

    public String getDestinationName() {
        return msg.getDestinationName();
    }

    public long getTimeStamp() {
        return msg.getTimeStamp();
    }

    public boolean isCached() {
        return msg.isCached();
    }

    public InboundMessage.ReplicationGroupMessageId getReplicationGroupMessageId() {
        return msg.getReplicationGroupMessageId();
    }

    public int getClassOfService() {
        return msg.getClassOfService();
    }

    public Long getSenderTimestamp() {
        return msg.getSenderTimestamp();
    }

    public String getSenderId() {
        return msg.getSenderId();
    }

    public boolean hasProperty(String s) {
        return msg.hasProperty(s);
    }

    public String getProperty(String s) {
        return msg.getProperty(s);
    }

    public String getPayloadAsString() {
        return msg.getPayloadAsString();
    }

    //    public Object getCorrelationKey() {
    //        return msg.getCorrelationKey();
    //    }

    public long getSequenceNumber() {
        return msg.getSequenceNumber();
    }

    public int getPriority() {
        return msg.getPriority();
    }

    public boolean hasContent() {
        return msg.hasContent();
    }

    public String getApplicationMessageId() {
        return msg.getApplicationMessageId();
    }

    public String getApplicationMessageType() {
        return msg.getApplicationMessageType();
    }

    public String dump() {
        return msg.dump();
    }

    public InteroperabilitySupport.RestInteroperabilitySupport getRestInteroperabilitySupport() {
        return msg.getRestInteroperabilitySupport();
    }

    public InboundMessage getMessage() {
        return msg;
    }

    public String getPayload() {
        return msg.getPayloadAsString();
    }

    public byte[] getPayloadAsBytes() {
        return msg.getPayloadAsBytes();
    }

    public Object getKey() {
        return msg.getCorrelationKey();
    }

    public long getExpiration() {
        return msg.getExpiration();
    }

    public String getCorrelationId() {
        return msg.getCorrelationId();
    }

    public Map<String, String> getProperties() {
        return msg.getProperties();
    }

}
