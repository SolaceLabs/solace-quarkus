package com.solace.quarkus.messaging.tracing;

import java.util.Map;

import io.opentelemetry.context.propagation.TextMapSetter;

public enum SolaceTraceTextMapSetter implements TextMapSetter<SolaceTrace> {
    INSTANCE;

    @Override
    public void set(SolaceTrace carrier, String key, String value) {
        if (carrier != null) {
            Map<String, String> properties = carrier.getMessageProperties();
            properties.put(key, value);
        }
    }
}
