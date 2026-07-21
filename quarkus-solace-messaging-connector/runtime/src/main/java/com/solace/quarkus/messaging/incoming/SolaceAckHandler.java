package com.solace.quarkus.messaging.incoming;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.solace.messaging.receiver.AcknowledgementSupport;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

class SolaceAckHandler {

    // Resolved dynamically rather than captured once: the receiver is rebuilt on
    // reconnect (DATAGO-141425 fix), so a captured reference would point at a
    // terminated receiver after reconnect. The supplier always returns the
    // current receiver held by SolaceIncomingChannel.
    private final Supplier<AcknowledgementSupport> ackSupportSupplier;

    public SolaceAckHandler(Supplier<AcknowledgementSupport> ackSupportSupplier) {
        this.ackSupportSupplier = ackSupportSupplier;
    }

    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg) {
        return Uni.createFrom().voidItem()
                .invoke(() -> ackSupportSupplier.get().ack(msg.getMessage()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .emitOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}