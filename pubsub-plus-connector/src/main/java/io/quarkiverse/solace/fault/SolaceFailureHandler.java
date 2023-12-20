package io.quarkiverse.solace.fault;

import static io.quarkiverse.solace.i18n.SolaceExceptions.ex;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import io.quarkiverse.solace.incoming.SolaceInboundMessage;

public interface SolaceFailureHandler {

    enum Strategy {
        /**
         * Mark the message as IGNORED, will continue processing with next message.
         */
        IGNORE,
        /**
         * Mark the message as FAILED, broker will redeliver the message.
         */
        FAIL,
        /**
         * Mark the message as REJECTED, broker will discard the message. The message will be moved to DMQ if DMQ is configured
         * for queue and DMQ Eligible is set on message.
         */
        DISCARD,
        /**
         * Will publish the message to configured error topic, on success the message will be acknowledged in the queue.
         */
        ERROR_TOPIC;

        public static Strategy from(String s) {
            if (s == null || s.equalsIgnoreCase("ignore")) {
                return IGNORE;
            }
            if (s.equalsIgnoreCase("fail")) {
                return FAIL;
            }
            if (s.equalsIgnoreCase("discard")) {
                return DISCARD;
            }
            if (s.equalsIgnoreCase("error_topic")) {
                return ERROR_TOPIC;
            }

            throw ex.illegalArgumentUnknownFailureStrategy(s);
        }
    }

    CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata);
}
