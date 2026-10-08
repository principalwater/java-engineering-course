package dev.principalwater.blog;

import dev.principalwater.blog.model.CommentRequest;
import dev.principalwater.blog.model.PostRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogServiceTest extends BlogIntegrationTest {
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void replacingPostContentPreservesItsLikesCommentsAndImage() {
        var post = createPost("Original", "Full text", "old");
        service.like(post.id());
        service.addComment(post.id(), new CommentRequest(null, "Comment", post.id()));
        service.updateImage(post.id(), IMAGE, "image/png");

        var updated = service.update(post.id(),
                new PostRequest(post.id(), "Edited", "New full text", List.of("new", "new")));

        assertEquals("Edited", updated.title());
        assertEquals("New full text", updated.text());
        assertEquals(List.of("new"), updated.tags());
        assertEquals(1, updated.likesCount());
        assertEquals(1, updated.commentsCount());
        assertArrayEquals(IMAGE, service.image(post.id()).bytes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseFailureRollsBackTheServicesWholeCreateOperation() {
        // Ошибка второго INSERT проверяет транзакцию сервиса без внешней транзакции теста.
        jdbc.getJdbcTemplate().execute("ALTER TABLE post_tags ADD CONSTRAINT reject_rollback_tag CHECK (tag <> 'rollback')");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> createPost("Rollback fixture", "Body", "rollback-first", "rollback"));

            assertTrue(service.list("Rollback fixture", 1, 5).posts().isEmpty());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM post_tags WHERE tag = 'rollback-first'",
                    Map.of(), Long.class));
        } finally {
            jdbc.getJdbcTemplate().execute("ALTER TABLE post_tags DROP CONSTRAINT reject_rollback_tag");
        }
    }

    @Test
    void feedCombinesPreviewModelsWithCorrectPageNavigation() {
        var empty = service.list("#navigation", 1, 2);
        assertTrue(empty.posts().isEmpty());
        assertEquals(1, empty.lastPage());
        assertFalse(empty.hasPrev());
        assertFalse(empty.hasNext());
        for (int index = 0; index < 5; index++) {
            createPost("Navigation " + index, "a".repeat(129), "navigation");
        }

        var first = service.list("#navigation", 1, 2);
        assertEquals(2, first.posts().size());
        assertEquals("a".repeat(128) + "…", first.posts().getFirst().text());
        assertFalse(first.hasPrev());
        assertTrue(first.hasNext());
        assertEquals(3, first.lastPage());

        var last = service.list("#navigation", 3, 2);
        assertEquals(1, last.posts().size());
        assertTrue(last.hasPrev());
        assertFalse(last.hasNext());
        assertEquals(3, last.lastPage());

        var pastLast = service.list("#navigation", 4, 2);
        assertTrue(pastLast.posts().isEmpty());
        assertFalse(pastLast.hasNext());
        assertEquals(3, pastLast.lastPage());
    }
}
