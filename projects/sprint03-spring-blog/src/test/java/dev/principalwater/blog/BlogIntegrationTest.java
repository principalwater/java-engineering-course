package dev.principalwater.blog;

import dev.principalwater.blog.config.DataConfig;
import dev.principalwater.blog.config.WebConfig;
import dev.principalwater.blog.model.Post;
import dev.principalwater.blog.model.PostRequest;
import dev.principalwater.blog.service.BlogService;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.transaction.annotation.Transactional;

@SpringJUnitWebConfig({DataConfig.class, WebConfig.class})
@TestPropertySource(properties = {
        "DB_URL=jdbc:h2:mem:blog-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "DB_USER=sa", "DB_PASSWORD=", "CORS_ORIGINS=http://localhost"
})
@Transactional
public abstract class BlogIntegrationTest {
    protected static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @Autowired
    protected BlogService service;

    protected Post createPost(String title, String text, String... tags) {
        return service.create(new PostRequest(null, title, text, List.of(tags)));
    }
}
