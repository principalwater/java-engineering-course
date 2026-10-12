package dev.principalwater.blog;

import dev.principalwater.blog.model.Post;
import dev.principalwater.blog.model.PostRequest;
import dev.principalwater.blog.service.BlogService;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = BlogApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
