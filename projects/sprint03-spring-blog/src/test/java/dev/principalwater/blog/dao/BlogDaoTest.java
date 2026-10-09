package dev.principalwater.blog.dao;

import dev.principalwater.blog.BlogIntegrationTest;
import dev.principalwater.blog.dao.BlogDao;
import dev.principalwater.blog.model.PostRequest;
import dev.principalwater.blog.model.SearchFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogDaoTest extends BlogIntegrationTest {
    @Autowired
    private BlogDao dao;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void combinesTitleAndEveryTagWithAnd() {
        long expected = insert("Spring SQL", "Body", "java", "jdbc");
        insert("Spring SQL", "Body", "java");
        insert("Other title", "Body", "java", "jdbc");
        var filter = new SearchFilter("Spring", List.of("java", "jdbc"));

        assertEquals(1, dao.count(filter));
        assertEquals(List.of(expected), dao.page(filter, 0, 5).stream().map(post -> post.id()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "' OR 1=1 --"})
    void titleSearchTreatsSqlMetacharactersAsLiteralText(String fragment) {
        long expected = insert("Literal " + fragment, "Body");
        insert("Literal ordinary text", "Body");
        var filter = new SearchFilter(fragment, List.of());

        assertEquals(1, dao.count(filter));
        assertEquals(expected, dao.page(filter, 0, 5).getFirst().id());
    }

    @Test
    void pagesNewestFirstAndReadsTagsAndCommentCounts() {
        insert("Storage page", "Body", "storage", "first");
        long middle = insert("Storage page", "Body", "storage", "second");
        long latest = insert("Storage page", "Body", "storage", "third");
        dao.insertComment(latest, "One");
        dao.insertComment(latest, "Two");
        var filter = new SearchFilter("Storage page", List.of("storage"));

        assertEquals(3, dao.count(filter));
        assertEquals(middle, dao.page(filter, 1, 1).getFirst().id());
        var newest = dao.page(filter, 0, 1).getFirst();
        assertEquals(latest, newest.id());
        assertEquals(List.of("storage", "third"), newest.tags());
        assertEquals(2, newest.commentsCount());
    }

    @Test
    void deletingPostCascadesToCommentsAndTags() {
        long id = insert("Cascade", "Body", "cascade");
        dao.insertComment(id, "Comment");

        assertEquals(1, dao.delete(id));
        assertTrue(dao.get(id).isEmpty());
        assertTrue(dao.comments(id).isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM post_tags WHERE post_id = :id",
                java.util.Map.of("id", id), Long.class));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentLikesNeverLoseAnIncrement() throws Exception {
        // Строка должна быть закоммичена до старта потоков с независимыми соединениями.
        long id = insert("Concurrent likes", "Body");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int worker = 0; worker < 6; worker++) {
                futures.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start");
                    }
                    int updated = 0;
                    for (int attempt = 0; attempt < 10; attempt++) {
                        updated += dao.incrementLikes(id);
                    }
                    return updated;
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertEquals(10, future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(60, dao.likes(id));
        } finally {
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            dao.delete(id);
            assertTrue(terminated);
        }
    }

    private long insert(String title, String text, String... tags) {
        return dao.insert(new PostRequest(null, title, text, List.of(tags)));
    }
}
