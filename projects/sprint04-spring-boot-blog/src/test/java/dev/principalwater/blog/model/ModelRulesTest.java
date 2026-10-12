package dev.principalwater.blog.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRulesTest {
    @Test
    void separatesTagsFromTheJoinedTitleFragment() {
        var filter = SearchFilter.parse("  Spring   #java Framework  #jdbc ");

        assertEquals("Spring Framework", filter.titleFragment());
        assertEquals(List.of("java", "jdbc"), filter.tags());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emptySearchDoesNotFilterPosts(String search) {
        var filter = SearchFilter.parse(search);

        assertEquals("", filter.titleFragment());
        assertTrue(filter.tags().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {127, 128, 129})
    void previewAddsEllipsisOnlyAfter128Characters(int length) {
        var post = new Post(1L, "Title", "a".repeat(length), List.of(), 0, 0);

        assertEquals(length > 128 ? "a".repeat(128) + "…" : post.text(), post.preview().text());
    }

    @Test
    void previewDoesNotSplitUnicodeOrChangeTheFullPost() {
        var post = new Post(1L, "Title", "😀".repeat(129), List.of("java"), 2, 3);

        assertEquals("😀".repeat(128) + "…", post.preview().text());
        assertEquals("😀".repeat(129), post.text());
    }
}
