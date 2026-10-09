package dev.principalwater.study.users.repository;

import dev.principalwater.study.users.model.UserPhoto;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserPhotoRepository extends ReactiveCrudRepository<UserPhoto, Long> {
    // Read-then-insert допускает гонку двух загрузок; H2 MERGE сохраняет фото одной SQL-операцией.
    @Modifying
    @Query("MERGE INTO user_photos (user_id, content_type, data) KEY(user_id) VALUES (:userId, :contentType, :data)")
    Mono<Integer> upsert(Long userId, String contentType, byte[] data);
}
