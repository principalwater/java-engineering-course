package dev.principalwater.blog.controller;

import dev.principalwater.blog.BlogIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.IMAGE_PNG;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PostControllerTest extends BlogIntegrationTest {
    @Autowired
    private ObjectMapper json;

    @Autowired
    private MockMvc mvc;

    @Test
    void postLifecycleUsesTheClientsJsonAndHttpMethods() throws Exception {
        String fullText = "Markdown body ".repeat(12);
        var created = mvc.perform(post("/api/posts").contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("title", "HTTP contract", "text", fullText,
                                "tags", List.of("http")))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.likesCount").value(0))
                .andExpect(jsonPath("$.commentsCount").value(0)).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();

        mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("HTTP contract"))
                .andExpect(jsonPath("$.text").value(fullText))
                .andExpect(jsonPath("$.tags[0]").value("http"));
        mvc.perform(post("/api/posts/{id}", id)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/posts").param("search", "HTTP #http")
                        .param("pageNumber", "1").param("pageSize", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.posts", hasSize(1)))
                .andExpect(jsonPath("$.posts[0].id").value(id))
                .andExpect(jsonPath("$.hasPrev").value(false)).andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.lastPage").value(1));
        mvc.perform(post("/api/posts/{id}/likes", id)).andExpect(status().isOk())
                .andExpect(content().string("1"));
        mvc.perform(put("/api/posts/{id}", id).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("id", id, "title", "Edited HTTP contract",
                                "text", "Edited body", "tags", List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edited HTTP contract"))
                .andExpect(jsonPath("$.tags", hasSize(0)));
        mvc.perform(delete("/api/posts/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void commentsStayBoundToTheirPostThroughCrud() throws Exception {
        long postId = createPost("Comment owner", "Body").id();
        long otherPostId = createPost("Other owner", "Body").id();
        var created = mvc.perform(post("/api/posts/{postId}/comments", postId).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("postId", postId, "text", "Original comment"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.postId").value(postId)).andReturn();
        long id = json.readTree(created.getResponse().getContentAsByteArray()).get("id").longValue();

        mvc.perform(get("/api/posts/{postId}/comments", postId)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/posts/{postId}/comments/{id}", postId, id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Original comment"));
        mvc.perform(get("/api/posts/{postId}/comments/{id}", otherPostId, id))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/posts/{postId}/comments/{id}", otherPostId, id).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("id", id, "postId", otherPostId, "text", "Hijacked"))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/posts/{postId}/comments/{id}", otherPostId, id))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/posts/{postId}/comments/{id}", postId, id).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("id", id, "postId", otherPostId, "text", "Mismatch"))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/posts/{postId}/comments/{id}", postId, id).contentType(APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("id", id, "postId", postId, "text", "Edited comment"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Edited comment"));
        mvc.perform(delete("/api/posts/{postId}/comments/{id}", postId, id)).andExpect(status().isOk());
        mvc.perform(get("/api/posts/{postId}/comments", postId)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void multipartImageRoundTripsWithItsDetectedTypeAndRejectsForgedContent() throws Exception {
        long id = createPost("Image owner", "Body").id();
        mvc.perform(get("/api/posts/{id}/image", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Image not found"));

        var image = new MockMultipartFile("image", "photo.png", "application/octet-stream", IMAGE);
        mvc.perform(multipart(HttpMethod.PUT, "/api/posts/{id}/image", id).file(image))
                .andExpect(status().isOk());
        mvc.perform(get("/api/posts/{id}/image", id)).andExpect(status().isOk())
                .andExpect(content().contentType(IMAGE_PNG)).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(IMAGE));

        var forged = new MockMultipartFile("image", "photo.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(HttpMethod.PUT, "/api/posts/{id}/image", id).file(forged))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts/{id}/image", id)).andExpect(status().isOk())
                .andExpect(content().bytes(IMAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\" \",\"text\":\"Body\",\"tags\":[]}",
            "{\"title\":\"Title\",\"tags\":[]}",
            "{\"title\":\"Title\",\"text\":\"Body\"}",
            "{\"title\":\"Title\",\"text\":\"Body\",\"tags\":[\"two words\"]}"
    })
    void invalidPostPayloadReturnsBadRequest(String body) throws Exception {
        mvc.perform(post("/api/posts").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }

    @Test
    void listRequiresItsParametersAndValidPagination() throws Exception {
        mvc.perform(get("/api/posts").param("pageNumber", "1").param("pageSize", "5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("search", "").param("pageNumber", "0").param("pageSize", "5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("search", "").param("pageNumber", "one").param("pageSize", "5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsAllowsTheConfiguredClientAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("http://localhost:8080/api/posts").header("Origin", "http://localhost")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost"));
        mvc.perform(options("http://localhost:8080/api/posts").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
