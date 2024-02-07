package com.solace.quarkus.messaging.tracing;

import org.eclipse.microprofile.reactive.messaging.Message;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessageOperation;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingSpanNameExtractor;
import io.smallrye.reactive.messaging.tracing.TracingUtils;

public class SolaceOpenTelemetryInstrumenter {

    private final Instrumenter<SolaceTrace, Void> instrumenter;

    public SolaceOpenTelemetryInstrumenter(Instrumenter<SolaceTrace, Void> instrumenter) {
        this.instrumenter = instrumenter;
    }

    public static SolaceOpenTelemetryInstrumenter createForIncoming() {
        return createInstrumenter(true);
    }

    public static SolaceOpenTelemetryInstrumenter createForOutgoing() {
        return createInstrumenter(false);
    }

    private static SolaceOpenTelemetryInstrumenter createInstrumenter(boolean incoming) {
        MessageOperation messageOperation = incoming ? MessageOperation.RECEIVE : MessageOperation.SEND;

        SolaceAttributeExtractor myExtractor = new SolaceAttributeExtractor();
        MessagingAttributesGetter<SolaceTrace, Void> attributesGetter = myExtractor.getMessagingAttributesGetter();
        var spanNameExtractor = MessagingSpanNameExtractor.create(attributesGetter, messageOperation);
        InstrumenterBuilder<SolaceTrace, Void> builder = Instrumenter.builder(GlobalOpenTelemetry.get(),
                "io.smallrye.reactive.messaging", spanNameExtractor);
        var attributesExtractor = MessagingAttributesExtractor.create(attributesGetter, messageOperation);

        builder
                .addAttributesExtractor(attributesExtractor)
                .addAttributesExtractor(myExtractor);

        if (incoming) {
            return new SolaceOpenTelemetryInstrumenter(builder.buildConsumerInstrumenter(SolaceTraceTextMapGetter.INSTANCE));
        } else {
            return new SolaceOpenTelemetryInstrumenter(builder.buildProducerInstrumenter(SolaceTraceTextMapSetter.INSTANCE));
        }
    }
    // </create-instrumener>

    public Message<?> traceIncoming(Message<?> message, SolaceTrace myTrace, boolean makeCurrent) {
        return TracingUtils.traceIncoming(instrumenter, message, myTrace, makeCurrent);
    }

    public void traceOutgoing(Message<?> message, SolaceTrace myTrace) {
        TracingUtils.traceOutgoing(instrumenter, message, myTrace);
    }

}
