package dev.principalwater.study.mvc;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public List<User> findAll() {
        return repository.findAll();
    }

    public void save(User user) {
        validate(user);
        repository.save(user);
    }

    public void update(long id, User user) {
        validate(user);
        if (repository.update(id, user) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
    }

    public void delete(long id) {
        if (repository.deleteById(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
    }

    private static void validate(User user) {
        if (user.firstName() == null || user.firstName().isBlank() || user.firstName().length() > 256
                || user.lastName() == null || user.lastName().isBlank() || user.lastName().length() > 256
                || user.age() == null || user.age() < 0 || user.age() > 150 || user.active() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректные данные пользователя");
        }
    }
}
