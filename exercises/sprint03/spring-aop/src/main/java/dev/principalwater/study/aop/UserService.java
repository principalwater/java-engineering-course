package dev.principalwater.study.aop;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

public class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    public void createUser(User user) {
        users.put(user.name(), user);
    }

    public User getUserByName(String name) {
        User user = users.get(name);
        if (user == null) {
            throw new NoSuchElementException("Пользователь не найден: " + name);
        }
        return user;
    }

    public void deleteUserByName(String name) {
        users.remove(name);
    }
}
