package dev.principalwater.store.service;

import dev.principalwater.store.TestBase;
import dev.principalwater.store.config.RecoveryProperties;
import dev.principalwater.store.dao.StoreUserRepository;
import dev.principalwater.store.model.StoreError;
import dev.principalwater.store.model.StoreException;
import dev.principalwater.store.runtime.RuntimeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryAvailabilityTest extends TestBase {
    // Письмо обещают только при включённой настройке, заданной публичной базе, отправителе и настроенном SMTP.
    @ParameterizedTest
    @CsvSource({"true,https://shop.example.test,no-reply@example.test,true,true",
            "false,https://shop.example.test,no-reply@example.test,true,false",
            "true,,no-reply@example.test,true,false",
            "true,https://shop.example.test,,true,false",
            "true,https://shop.example.test,no-reply@example.test,false,false"})
    void recoveryIsOfferedOnlyWhenEveryPieceOfTheMailPathIsConfigured(boolean enabled, String base, String sender,
                                                                       boolean smtp, boolean expected) {
        var properties = new RecoveryProperties(enabled, base == null ? "" : base, sender == null ? "" : sender);
        assertEquals(expected, service(properties, smtp).available());
    }

    @ParameterizedTest
    @CsvSource({"8,2", "64,8"})
    void recoveryRequestsInFlightAreBoundedAndTheSlotIsReleasedOnCancellation(int httpBudget, int recoveryLimit) {
        var properties = new RecoveryProperties(true, "https://shop.example.test", "no-reply@example.test");
        var users = mock(StoreUserRepository.class);
        when(users.findByEmail(any())).thenReturn(Mono.never());
        var recovery = new RecoveryService(properties, mailer(properties, true), users, null, null, Schedulers.immediate(), null,
                Clock.systemUTC(), new SimpleMeterRegistry(), new RuntimeProperties(2, 1, 32, 256, httpBudget));
        var running = new ArrayList<Disposable>();
        for (int index = 0; index < recoveryLimit; index++) {
            running.add(recovery.request("user" + index + "@example.test").subscribe());
        }
        // Переполнение отклоняется до паузы; восстановление не занимает весь HTTP-бюджет даже на слабом стенде.
        StepVerifier.create(recovery.request("anyone@example.test")).expectErrorSatisfies(failure ->
                assertEquals(StoreError.RECOVERY_BUSY, ((StoreException) failure).getError())).verify(Duration.ofSeconds(2));
        running.getFirst().dispose();
        StepVerifier.create(recovery.request("again@example.test")).expectSubscription().expectNoEvent(Duration.ofMillis(200)).thenCancel().verify();
        running.forEach(Disposable::dispose);
    }

    @Test
    void withoutSmtpTheMailerIsNeverReady() {
        var properties = new RecoveryProperties(true, "https://shop.example.test", "no-reply@example.test");
        assertEquals(false, mailer(properties, false).ready());
    }

    private static RecoveryService service(RecoveryProperties properties, boolean smtp) {
        return new RecoveryService(properties, mailer(properties, smtp), null, null, null, Schedulers.immediate(), null,
                Clock.systemUTC(), new SimpleMeterRegistry(), new RuntimeProperties(2, 1, 32, 256, 64));
    }

    @SuppressWarnings("unchecked")
    private static RecoveryMailer mailer(RecoveryProperties properties, boolean smtp) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(smtp ? mock(JavaMailSender.class) : null);
        return new RecoveryMailer(provider, properties, Schedulers.immediate(), new SimpleMeterRegistry());
    }
}
