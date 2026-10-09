package dev.principalwater.study;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NestedLifecycleTest {
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUpOuter() { events.add("outer-before"); }

    @AfterEach
    void verifyCleanupOrder() {
        events.add("outer-after");
        assertEquals(List.of("outer-before", "inner-before", "test",
                "inner-after", "outer-after"), events);
    }

    @Nested
    class WithInnerFixture {
        @BeforeEach
        void setUpInner() { events.add("inner-before"); }

        @AfterEach
        void tearDownInner() { events.add("inner-after"); }

        @Test
        void enclosesTheInnerFixture() {
            events.add("test");
            assertEquals(List.of("outer-before", "inner-before", "test"), events);
        }
    }
}
