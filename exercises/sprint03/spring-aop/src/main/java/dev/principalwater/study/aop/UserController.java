package dev.principalwater.study.aop;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{name}")
    public User getUser(@PathVariable("name") String name) {
        return userService.getUserByName(name);
    }

    @PostMapping
    public void createUser(@RequestBody User user) {
        userService.createUser(user);
    }

    @DeleteMapping("/{name}")
    public void deleteUser(@PathVariable("name") String name) {
        userService.deleteUserByName(name);
    }
}
