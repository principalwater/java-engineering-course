package dev.principalwater.study.users;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UsersHttpTest {
    @Autowired
    private TestRestTemplate http;

    /** Реальный стек MVC/Jackson/JDBC должен сохранять CRUD и отклонять невалидную запись. */
    @Test
    void crudPreservesUtf8AndRejectsInvalidData() {
        List<User> before = users();
        assertTrue(before.stream().anyMatch(user -> user.firstName().equals("Анна")));
        User input = new User(null, "O'Коннор", "Проверка", 35, true);
        assertEquals(HttpStatus.CREATED, http.postForEntity("/users", input, Void.class).getStatusCode());
        User created = users().stream().filter(user -> user.lastName().equals(input.lastName())).findFirst().orElseThrow();
        long id = created.id();
        try {
            assertEquals(new User(id, input.firstName(), input.lastName(), 35, true), created);
            User changed = new User(null, input.firstName(), input.lastName(), 36, false);
            assertEquals(HttpStatus.NO_CONTENT, http.exchange("/users/" + id, HttpMethod.PUT,
                    new HttpEntity<>(changed), Void.class).getStatusCode());
            List<User> updated = users();
            assertTrue(updated.contains(new User(id, input.firstName(), input.lastName(), 36, false)));
            User invalid = new User(null, " ", input.lastName(), -1, true);
            assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity("/users", invalid, Void.class).getStatusCode());
            assertEquals(updated, users());
        } finally {
            assertEquals(HttpStatus.NO_CONTENT, http.exchange("/users/" + id, HttpMethod.DELETE,
                    HttpEntity.EMPTY, Void.class).getStatusCode());
        }
        assertEquals(before, users());
        assertEquals(HttpStatus.NOT_FOUND, http.exchange("/users/" + id, HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class).getStatusCode());
    }

    private List<User> users() {
        var response = http.getForEntity("/users", User[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return Arrays.asList(response.getBody());
    }
}
