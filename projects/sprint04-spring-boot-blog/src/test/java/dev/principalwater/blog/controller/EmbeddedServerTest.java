package dev.principalwater.blog.controller;

import dev.principalwater.blog.BlogApplication;
import dev.principalwater.blog.model.Post;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

import static dev.principalwater.blog.model.BlogLimits.MAX_IMAGE_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BlogApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:blog-http-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class EmbeddedServerTest {
    @Autowired
    private TestRestTemplate http;

    @Test
    void tomcatRejectsOversizedMultipartWithoutReplacingThePost() {
        var created = http.postForEntity("/api/posts",
                Map.of("title", "Upload limit", "text", "Original body", "tags", List.of()), Post.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = created.getBody().id();
        try {
            var file = new ByteArrayResource(new byte[MAX_IMAGE_BYTES + 1]) {
                @Override
                public String getFilename() {
                    return "oversized.png";
                }
            };
            var multipart = new LinkedMultiValueMap<String, Object>();
            multipart.add("image", file);
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            var response = http.exchange("/api/posts/{id}/image", HttpMethod.PUT,
                    new HttpEntity<>(multipart, headers), JsonNode.class, id);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(response.getBody().has("error")).isTrue();
            assertThat(http.getForEntity("/api/posts/{id}", Post.class, id).getBody().text())
                    .isEqualTo("Original body");
            assertThat(http.getForEntity("/api/posts/{id}/image", String.class, id).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            // HTTP-запросы исполняются в другом потоке: @Transactional теста их не откатит.
            http.delete("/api/posts/{id}", id);
        }
    }
}
