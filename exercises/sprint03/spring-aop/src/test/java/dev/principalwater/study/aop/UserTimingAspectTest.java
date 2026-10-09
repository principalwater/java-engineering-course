package dev.principalwater.study.aop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class UserTimingAspectTest {
    @Test
    void measuresOnlyStringMethodsAndPreservesTheirResultAndFailure() throws Exception {
        var output = new ByteArrayOutputStream();
        var handler = new StreamHandler(output, new SimpleFormatter());
        handler.setEncoding(StandardCharsets.UTF_8.name());
        Logger logger = Logger.getLogger(UserTimingAspect.class.getName());
        logger.addHandler(handler);
        try (var context = new AnnotationConfigApplicationContext(AppConfiguration.class)) {
            UserController controller = context.getBean(UserController.class);
            User ada = new User("Ada");
            controller.createUser(ada);
            handler.flush();
            assertEquals("", output.toString(StandardCharsets.UTF_8));

            assertSame(ada, controller.getUser("Ada"));
            controller.deleteUser("Ada");
            NoSuchElementException failure = assertThrows(NoSuchElementException.class,
                    () -> controller.getUser("Ada"));
            assertEquals("Пользователь не найден: Ada", failure.getMessage());

            handler.flush();
            String logs = output.toString(StandardCharsets.UTF_8);
            assertEquals(2, logs.lines().filter(line -> line.contains("method=UserController.getUser(..) args=[Ada] elapsedNanos=")).count());
            assertEquals(1, logs.lines().filter(line -> line.contains("method=UserController.deleteUser(..) args=[Ada] elapsedNanos=")).count());
            assertEquals(3, logs.lines().filter(line -> line.matches(".*elapsedNanos=[0-9]+$")).count());
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }
    }
}
