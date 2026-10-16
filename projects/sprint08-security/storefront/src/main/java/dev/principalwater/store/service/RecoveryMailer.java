package dev.principalwater.store.service;

import dev.principalwater.store.config.RecoveryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
public class RecoveryMailer {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryMailer.class);
    private static final String MAIL_METER = "store.recovery.mail";
    private static final String OUTCOME_TAG = "outcome";
    private final JavaMailSender sender;
    private final String from;
    private final Scheduler mailScheduler;
    private final MeterRegistry metrics;

    public RecoveryMailer(ObjectProvider<JavaMailSender> sender, RecoveryProperties properties,
                          Scheduler recoveryMailScheduler, MeterRegistry metrics) {
        // Без spring.mail.host JavaMailSender не создаётся: восстановление остаётся выключенным, а не «отправленным».
        this.sender = sender.getIfAvailable();
        this.from = properties.sender();
        this.mailScheduler = recoveryMailScheduler;
        this.metrics = metrics;
    }

    public boolean ready() {
        return sender != null && !from.isEmpty();
    }

    // Отправка не ждёт ответа SMTP: время ответа клиенту не зависит от того, существует ли адрес.
    public void dispatch(String recipient, String link) {
        Mono.fromRunnable(() -> sender.send(message(recipient, link))).subscribeOn(mailScheduler)
                .subscribe(ignored -> { }, this::failed, () -> metrics.counter(MAIL_METER, OUTCOME_TAG, "sent").increment());
    }

    private SimpleMailMessage message(String recipient, String link) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject("Восстановление доступа · Мастерская");
        message.setText("""
                Здравствуйте!

                Вы запросили восстановление доступа к аккаунту в «Мастерской». Чтобы задать новый пароль, откройте ссылку.
                Она действует 15 минут и работает один раз:

                %s

                Если вы ничего не запрашивали, проигнорируйте письмо: пароль останется прежним.
                """.formatted(link));
        return message;
    }

    // Сообщение исключения SMTP может содержать адрес получателя, поэтому в журнал идёт только тип причины.
    private void failed(Throwable failure) {
        boolean saturated = failure instanceof RejectedExecutionException;
        metrics.counter(MAIL_METER, OUTCOME_TAG, saturated ? "rejected" : "failed").increment();
        LOG.warn("Password recovery mail was not delivered: {}",
                NestedExceptionUtils.getMostSpecificCause(failure).getClass().getName());
    }
}
