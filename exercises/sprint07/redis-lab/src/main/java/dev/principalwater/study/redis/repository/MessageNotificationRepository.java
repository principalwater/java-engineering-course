package dev.principalwater.study.redis.repository;

import dev.principalwater.study.redis.model.MessageNotification;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface MessageNotificationRepository extends CrudRepository<MessageNotification, String> {
    List<MessageNotification> findAllByReceiver(String receiver);
}
