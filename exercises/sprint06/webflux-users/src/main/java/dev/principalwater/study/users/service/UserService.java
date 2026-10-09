package dev.principalwater.study.users.service;

import dev.principalwater.study.users.model.User;
import dev.principalwater.study.users.model.UserForm;
import dev.principalwater.study.users.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    public Flux<User> findAll(String lastName) {
        return lastName.isBlank() ? users.findAllByOrderByIdAsc()
                : users.findByLastNameContainingIgnoreCaseOrderByIdAsc(lastName.strip());
    }

    public Mono<User> findById(Long id) { return users.findById(id); }
    public Mono<User> create(UserForm form) { return users.save(form.toUser(null)); }
    public Mono<User> update(Long id, UserForm form) {
        return users.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(existing -> users.save(form.toUser(existing.id())));
    }
    public Mono<Void> delete(Long id) { return users.deleteById(id); }
}
