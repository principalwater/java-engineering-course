package dev.principalwater.study;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Проверка контракта Jupiter: дочерняя очистка должна завершаться раньше родительской.
class LifecycleInheritanceTest extends ParentLifecycle {
    @BeforeAll
    static void beforeChildClass() { events.add("child-before-all"); }

    @BeforeEach
    void beforeChildTest() { events.add("child-before-each"); }

    @Test
    void lifecyclePreservesResourceNesting() {
        events.add("test");
        assertEquals(List.of("parent-before-all", "child-before-all",
                "parent-before-each", "child-before-each", "test"), events);
    }

    @AfterEach
    void afterChildTest() { events.add("child-after-each"); }

    @AfterAll
    static void afterChildClass() { events.add("child-after-all"); }
}

abstract class ParentLifecycle {
    protected static final List<String> events = new ArrayList<>();

    @BeforeAll
    static void beforeParentClass() { events.add("parent-before-all"); }

    @BeforeEach
    void beforeParentTest() { events.add("parent-before-each"); }

    @AfterEach
    void afterParentTest() { events.add("parent-after-each"); }

    @AfterAll
    static void afterParentClass() {
        events.add("parent-after-all");
        assertEquals(List.of("parent-before-all", "child-before-all",
                "parent-before-each", "child-before-each", "test", "child-after-each",
                "parent-after-each", "child-after-all", "parent-after-all"), events);
        System.out.println("Порядок жизненного цикла: " + events);
    }
}
