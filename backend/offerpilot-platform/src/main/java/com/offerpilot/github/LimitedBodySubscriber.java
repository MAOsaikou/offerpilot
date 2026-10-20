package com.offerpilot.github;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int limit;
    private int count;
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit) { this.limit = limit; }
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    public void onNext(List<ByteBuffer> buffers) {
        long size = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
        if (size > limit - count) {
            subscription.cancel(); delegate.onError(new IllegalStateException("GitHub response exceeds limit"));
        } else { count += (int) size; delegate.onNext(buffers); }
    }
    public void onError(Throwable error) { delegate.onError(error); }
    public void onComplete() { delegate.onComplete(); }
}
