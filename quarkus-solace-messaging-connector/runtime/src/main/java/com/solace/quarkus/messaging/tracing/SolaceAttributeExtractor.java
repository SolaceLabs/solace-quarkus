package com.solace.quarkus.messaging.tracing;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.incubator.semconv.messaging.MessagingAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;

public class SolaceAttributeExtractor implements AttributesExtractor<SolaceTrace, Void> {
    private final MessagingAttributesGetter<SolaceTrace, Void> messagingAttributesGetter;

    public SolaceAttributeExtractor() {
        this.messagingAttributesGetter = new SolaceMessagingAttributesGetter();
    }

    @Override
    public void onStart(AttributesBuilder attributesBuilder, Context context, SolaceTrace solaceTrace) {
        attributesBuilder.put("messaging.solace.partition_number", solaceTrace.getPartitionKey());
    }

    @Override
    public void onEnd(AttributesBuilder attributesBuilder, Context context, SolaceTrace solaceTrace, Void unused,
            Throwable throwable) {

    }

    public MessagingAttributesGetter<SolaceTrace, Void> getMessagingAttributesGetter() {
        return messagingAttributesGetter;
    }

    private static final class SolaceMessagingAttributesGetter implements MessagingAttributesGetter<SolaceTrace, Void> {
        @Override
        public String getSystem(final SolaceTrace solaceTrace) {
            return "SolacePubSub+";
        }

        //        @Override
        //        public String getDestinationKind(SolaceTrace solaceTrace) {
        //            return solaceTrace.getDestinationKind();
        //        }

        @Override
        public String getDestination(final SolaceTrace solaceTrace) {
            return solaceTrace.getTopic();
        }

        @Override
        public String getDestinationTemplate(SolaceTrace solaceTrace) {
            return "";
        }

        @Override
        public boolean isTemporaryDestination(final SolaceTrace solaceTrace) {
            return false;
        }

        @Override
        public boolean isAnonymousDestination(SolaceTrace solaceTrace) {
            return false;
        }

        @Override
        public String getConversationId(final SolaceTrace solaceTrace) {
            return solaceTrace.getCorrelationId();
        }

        @Override
        public Long getMessagePayloadSize(final SolaceTrace solaceTrace) {
            return solaceTrace.getPayloadSize();
        }

        @Override
        public Long getMessagePayloadCompressedSize(final SolaceTrace solaceTrace) {
            return null;
        }

        @Override
        public Long getMessageBodySize(SolaceTrace solaceTrace) {
            return 0L;
        }

        @Override
        public Long getMessageEnvelopeSize(SolaceTrace solaceTrace) {
            return 0L;
        }

        @Override
        public String getMessageId(final SolaceTrace solaceTrace, final Void unused) {
            return solaceTrace.getMessageId();
        }

        @Override
        public String getClientId(SolaceTrace solaceTrace) {
            return "";
        }

        @Override
        public Long getBatchMessageCount(SolaceTrace solaceTrace, Void unused) {
            return 0L;
        }

    }
}
