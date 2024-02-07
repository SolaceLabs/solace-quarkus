package com.solace.quarkus.messaging.tracing;

import java.util.ArrayList;
import java.util.Map;

import io.opentelemetry.context.propagation.TextMapGetter;

public enum SolaceTraceTextMapGetter implements TextMapGetter<SolaceTrace> {
    INSTANCE;

    @Override
    public Iterable<String> keys(SolaceTrace carrier) {
        Map<String, String> headers = carrier.getMessageProperties();
        return new ArrayList<>(headers.keySet());
    }

    @Override
    public String get(final SolaceTrace carrier, final String key) {
        if (carrier != null) {
            Map<String, String> properties = carrier.getMessageProperties();
            if (properties != null) {
                return properties.get(key);
            }
        }
        return null;
    }
}
