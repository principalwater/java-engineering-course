package dev.principalwater.study.reactive;

import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IteratorPublisher<T> implements Flow.Publisher<T> {
    private final Iterator<T> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    public IteratorPublisher(Iterator<T> source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber);
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onError(new IllegalStateException("Iterator already has a subscriber"));
            return;
        }
        IteratorSubscription subscription = new IteratorSubscription(subscriber);
        subscriber.onSubscribe(subscription);
        subscription.drain();
    }

    private final class IteratorSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private volatile boolean stopped;
        private boolean draining;
        private long demand;
        private Throwable failure;

        private IteratorSubscription(Flow.Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public synchronized void request(long n) {
            if (stopped) return;
            if (n <= 0) {
                failure = new IllegalArgumentException("Demand must be positive");
            } else {
                demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
            }
            drain();
        }

        @Override
        public void cancel() {
            stopped = true;
        }

        // shortcut: синхронный Iterator и callbacks должны быстро возвращаться; для I/O нужен отдельный адаптер.
        private synchronized void drain() {
            if (draining || stopped) return;
            draining = true;
            try {
                while (!stopped) {
                    if (failure != null) {
                        stopped = true;
                        subscriber.onError(failure);
                        return;
                    }
                    boolean hasNext;
                    T value = null;
                    try {
                        hasNext = source.hasNext();
                        if (hasNext) {
                            if (demand == 0) return;
                            value = Objects.requireNonNull(source.next(), "Iterator produced null");
                        }
                    } catch (RuntimeException exception) {
                        stopped = true;
                        subscriber.onError(exception);
                        return;
                    }
                    if (!hasNext) {
                        stopped = true;
                        subscriber.onComplete();
                        return;
                    }
                    if (demand != Long.MAX_VALUE) demand--;
                    subscriber.onNext(value);
                }
            } finally {
                draining = false;
            }
        }
    }
}
