package dev.principalwater.bank.notifications.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Отдельный процесс только переоткрывает страницу; штатный экземпляр доставляет её по outbox.
@Component
@Profile("notification-replay")
public class NotificationReplayCommand implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationReplayCommand.class);
    private final NotificationReplay replay;
    private final ConfigurableApplicationContext context;
    private final long after;
    private final int limit;

    public NotificationReplayCommand(
            NotificationReplay replay,
            ConfigurableApplicationContext context,
            @Value("${bank.notification.replay.after:0}") long after,
            @Value("${bank.notification.replay.limit:1000}") int limit) {
        this.replay = replay;
        this.context = context;
        this.after = after;
        this.limit = limit;
    }

    @Override
    public void run(ApplicationArguments args) {
        NotificationReplay.Page page = replay.requeue(after, limit);
        LOG.info(
                "Notification replay queued count={} nextSequence={}",
                page.count(),
                page.nextSequence());
        SpringApplication.exit(context, () -> 0);
    }
}
