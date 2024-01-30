package com.solace.quarkus.messaging.logging;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;

public class SolaceTestAppender extends AppenderSkeleton {
    private List<LoggingEvent> log = new ArrayList<>();

    @Override
    protected void append(LoggingEvent loggingEvent) {
        log.add(loggingEvent);
    }

    @Override
    public void close() {

    }

    @Override
    public boolean requiresLayout() {
        return false;
    }

    public List<LoggingEvent> getLog() {
        return new ArrayList<LoggingEvent>(log);
    }
}
