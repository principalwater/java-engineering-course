package dev.principalwater.store.payment;

import dev.principalwater.store.ReactiveTestBase;
import dev.principalwater.store.dao.CheckoutIntentRepository;
import dev.principalwater.store.service.CheckoutService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryTest extends ReactiveTestBase {
    @Test
    void slowPaymentDoesNotBlockOtherIntentsAndConcurrencyStaysBounded() {
        var repository = mock(CheckoutIntentRepository.class);
        var checkout = mock(CheckoutService.class);
        var clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
        var pending = new ArrayList<CheckoutIntent>();
        var responses = new ArrayList<Sinks.One<Long>>();
        var started = new AtomicInteger();
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        for (int index = 0; index < 8; index++) {
            pending.add(new CheckoutIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 100, clock.instant()));
            responses.add(Sinks.one());
        }
        when(repository.pending(any(), anyInt())).thenReturn(Flux.fromIterable(pending));
        when(repository.markAttempt(any(), any(), any())).thenReturn(Mono.just(1));
        when(checkout.resume(any())).thenAnswer(call -> Mono.defer(() -> {
            int index = pending.indexOf(call.getArgument(0));
            started.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            return responses.get(index).asMono().doOnTerminate(active::decrementAndGet);
        }));
        var recovery = new PaymentRecovery(repository, checkout, clock);

        StepVerifier.create(recovery.recover().then(Mono.defer(recovery::recover))).then(() -> {
            assertEquals(2, started.get(), "Recovery must keep two independent payments in flight");
            for (int index = 1; index < responses.size(); index++) {
                assertEquals(Sinks.EmitResult.OK, responses.get(index).tryEmitValue((long) index));
            }
            assertEquals(8, started.get(), "An unfinished oldest payment must not hold back later intents");
            assertEquals(2, peak.get(), "Recovery must reserve HTTP and SQL capacity for foreground traffic");
            var overlapping = recovery.recover();
            StepVerifier.create(overlapping.then(overlapping)).expectComplete().verify(java.time.Duration.ofSeconds(1));
            verify(repository, times(1)).pending(any(), anyInt());
            responses.getFirst().tryEmitValue(1L);
        }).verifyComplete();
        verify(repository, times(2)).pending(any(), anyInt());
        assertEquals(16, started.get(), "Completion must release the guard before the next recovery subscribes");
    }
    @Test
    void stuckAttemptTimesOutAndTheNextScheduledRunIsNotBlocked() {
        var repository = mock(CheckoutIntentRepository.class);
        var checkout = mock(CheckoutService.class);
        var clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
        var intent = new CheckoutIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 100, clock.instant());
        when(repository.pending(any(), anyInt())).thenReturn(Flux.just(intent));
        when(repository.markAttempt(any(), any(), any())).thenReturn(Mono.just(1));
        when(checkout.resume(intent)).thenReturn(Mono.never(), Mono.just(1L));
        var recovery = new PaymentRecovery(repository, checkout, clock);

        StepVerifier.withVirtualTime(recovery::recover).thenAwait(java.time.Duration.ofSeconds(15)).verifyComplete();
        StepVerifier.create(recovery.recover()).verifyComplete();
        verify(checkout, times(2)).resume(intent);
    }
    @Test
    void stalledQueueReadTimesOutAndReleasesTheSchedulerForTheNextRun() {
        var repository = mock(CheckoutIntentRepository.class);
        var checkout = mock(CheckoutService.class);
        when(repository.pending(any(), anyInt())).thenReturn(Flux.never(), Flux.never(), Flux.empty());
        var recovery = new PaymentRecovery(repository, checkout, Clock.systemUTC());

        StepVerifier.create(recovery.recover()).thenCancel().verify();
        StepVerifier.withVirtualTime(recovery::recover).thenAwait(java.time.Duration.ofSeconds(15))
                .expectComplete().verify(java.time.Duration.ofSeconds(1));
        StepVerifier.create(recovery.recover()).verifyComplete();
        verify(repository, times(3)).pending(any(), anyInt());
        verifyNoInteractions(checkout);
    }

}
