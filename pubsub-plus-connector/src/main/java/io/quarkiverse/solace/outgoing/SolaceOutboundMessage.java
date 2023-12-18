package io.quarkiverse.solace.outgoing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

public class SolaceOutboundMessage<T> implements Message<T> {

    public static <T> SolaceOutboundMessage<T> of(T value, SolaceOutboundMetadata outboundMetadata,
            Supplier<CompletionStage<Void>> ack,
            Function<Throwable, CompletionStage<Void>> nack) {
        return new SolaceOutboundMessage<>(value, ack, nack, outboundMetadata);
    }

    private final T value;
    private final Supplier<CompletionStage<Void>> ack;
    private final Function<Throwable, CompletionStage<Void>> nack;
    private final Metadata metadata;
    private final SolaceOutboundMetadata outboundMetadata;

    public SolaceOutboundMessage(T value,
            Supplier<CompletionStage<Void>> ack,
            Function<Throwable, CompletionStage<Void>> nack,
            SolaceOutboundMetadata outboundMetadata) {
        this.outboundMetadata = outboundMetadata;
        this.value = value;
        this.ack = ack;
        this.nack = nack;
        this.metadata = Metadata.of(outboundMetadata);
    }

    @Override
    public T getPayload() {
        return value;
    }

    @Override
    public CompletionStage<Void> ack() {
        if (ack == null) {
            return CompletableFuture.completedFuture(null);
        } else {
            return ack.get();
        }
    }

    @Override
    public CompletionStage<Void> nack(Throwable reason, Metadata metadata) {
        return Message.super.nack(reason, metadata);
    }

    @Override
    public Supplier<CompletionStage<Void>> getAck() {
        return ack;
    }

    @Override
    public Function<Throwable, CompletionStage<Void>> getNack() {
        return nack;
    }

    @Override
    public Metadata getMetadata() {
        return metadata;
    }

}
