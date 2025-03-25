package com.solace.quarkus.messaging.tracing;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.reactive.messaging.Message;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.incubator.semconv.messaging.MessageOperation;
import io.opentelemetry.instrumentation.api.incubator.semconv.messaging.MessagingAttributesExtractor;
import io.opentelemetry.instrumentation.api.incubator.semconv.messaging.MessagingAttributesGetter;
import io.opentelemetry.instrumentation.api.incubator.semconv.messaging.MessagingSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.smallrye.reactive.messaging.tracing.TracingUtils;

public class SolaceOpenTelemetryInstrumenter {

    private final Instrumenter<SolaceTrace, Void> instrumenter;

    public SolaceOpenTelemetryInstrumenter(Instrumenter<SolaceTrace, Void> instrumenter) {
        this.instrumenter = instrumenter;
    }

    public static SolaceOpenTelemetryInstrumenter createForIncoming(Instance<OpenTelemetry> openTelemetryInstance) {
        return createInstrumenter(TracingUtils.getOpenTelemetry(openTelemetryInstance), true);
    }

    public static SolaceOpenTelemetryInstrumenter createForOutgoing(Instance<OpenTelemetry> openTelemetryInstance) {
        return createInstrumenter(TracingUtils.getOpenTelemetry(openTelemetryInstance), false);
    }

    private static SolaceOpenTelemetryInstrumenter createInstrumenter(OpenTelemetry openTelemetry, boolean incoming) {
        MessageOperation messageOperation = incoming ? MessageOperation.RECEIVE : MessageOperation.PUBLISH;

        SolaceAttributeExtractor myExtractor = new SolaceAttributeExtractor();
        MessagingAttributesGetter<SolaceTrace, Void> attributesGetter = myExtractor.getMessagingAttributesGetter();
        var spanNameExtractor = MessagingSpanNameExtractor.create(attributesGetter, messageOperation);
        InstrumenterBuilder<SolaceTrace, Void> builder = Instrumenter.builder(openTelemetry,
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
