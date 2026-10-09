package dev.principalwater.study.users;

import java.util.List;

public interface UserRepository {
    List<User> findAll();
    void save(User user);
    int update(long id, User user);
    int deleteById(long id);
}
