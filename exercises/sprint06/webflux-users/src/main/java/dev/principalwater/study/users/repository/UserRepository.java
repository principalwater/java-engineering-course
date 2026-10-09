package dev.principalwater.study.users.repository;

import dev.principalwater.study.users.model.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {
    Flux<User> findAllByOrderByIdAsc();
    Flux<User> findByLastNameContainingIgnoreCaseOrderByIdAsc(String lastName);
}
