package dev.principalwater.study.reactive;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class IteratorPublisherTest {
    @Test
    void demandIsBoundedAndCompletionIsTerminal() {
        RecordingSubscriber<Number> subscriber = new RecordingSubscriber<>();
        new IteratorPublisher<>(List.of(1, 2, 3).iterator()).subscribe(subscriber);
        assertTrue(subscriber.values.isEmpty());
        subscriber.subscription.request(2);
        assertEquals(List.of(1, 2), subscriber.values);
        assertEquals(0, subscriber.completions);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.subscription.request(1);
        subscriber.subscription.request(0);
        assertEquals(List.of(1, 2, 3), subscriber.values);
        assertEquals(1, subscriber.completions);
        assertEquals(0, subscriber.errors);
    }

    @Test
    void reentrantDemandDoesNotGrowTheCallStack() {
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>() {
            @Override public void onNext(Integer value) {
                super.onNext(value);
                subscription.request(Long.MAX_VALUE);
            }
        };
        new IteratorPublisher<>(IntStream.range(0, 10_000).boxed().iterator()).subscribe(subscriber);
        subscriber.subscription.request(1);
        assertEquals(IntStream.range(0, 10_000).boxed().toList(), subscriber.values);
        assertEquals(1, subscriber.completions);
        assertEquals(0, subscriber.errors);
    }

    @Test
    void cancellationInsideCallbackStopsDelivery() {
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>() {
            @Override public void onNext(Integer value) {
                super.onNext(value);
                subscription.cancel();
            }
        };
        new IteratorPublisher<>(List.of(1, 2, 3).iterator()).subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        subscriber.subscription.request(1);
        assertEquals(List.of(1), subscriber.values);
        assertEquals(0, subscriber.completions);
        assertEquals(0, subscriber.errors);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidDemandProducesOneTerminalError(long demand) {
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        new IteratorPublisher<>(List.of(1, 2, 3).iterator()).subscribe(subscriber);
        subscriber.subscription.request(demand);
        subscriber.subscription.request(1);
        assertInstanceOf(IllegalArgumentException.class, subscriber.failure);
        assertEquals(1, subscriber.errors);
        assertTrue(subscriber.values.isEmpty());
        assertEquals(0, subscriber.completions);
    }

    @Test
    void iteratorFailureIsDeliveredAsAnError() {
        RuntimeException sourceFailure = new IllegalStateException("Source unavailable");
        Iterator<Integer> source = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Integer next() { throw sourceFailure; }
        };
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        new IteratorPublisher<>(source).subscribe(subscriber);
        subscriber.subscription.request(1);
        subscriber.subscription.request(1);
        assertSame(sourceFailure, subscriber.failure);
        assertEquals(1, subscriber.errors);
        assertEquals(0, subscriber.completions);
    }

    @Test
    void secondSubscriberIsRejectedWithoutConsumingTheIterator() {
        IteratorPublisher<Integer> publisher = new IteratorPublisher<>(List.of(1, 2, 3).iterator());
        RecordingSubscriber<Integer> first = new RecordingSubscriber<>();
        RecordingSubscriber<Integer> second = new RecordingSubscriber<>();
        publisher.subscribe(first);
        publisher.subscribe(second);
        assertNotNull(second.subscription);
        assertInstanceOf(IllegalStateException.class, second.failure);
        first.subscription.request(Long.MAX_VALUE);
        assertEquals(List.of(1, 2, 3), first.values);
        assertTrue(second.values.isEmpty());
    }

    @Test
    void emptyIteratorCompletesWithoutDemand() {
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        new IteratorPublisher<>(List.<Integer>of().iterator()).subscribe(subscriber);
        assertEquals(1, subscriber.completions);
        subscriber.subscription.request(1);
        assertTrue(subscriber.values.isEmpty());
        assertEquals(1, subscriber.completions);
        assertEquals(0, subscriber.errors);
    }

    private static class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        final List<T> values = new ArrayList<>();
        Flow.Subscription subscription;
        Throwable failure;
        int completions;
        int errors;

        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(T value) { assertNotNull(subscription); values.add(value); }
        @Override public void onError(Throwable value) { assertNotNull(subscription); failure = value; errors++; }
        @Override public void onComplete() { assertNotNull(subscription); completions++; }
    }
}
