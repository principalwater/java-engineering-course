package dev.principalwater.blog.controller;

import dev.principalwater.blog.model.*;
import dev.principalwater.blog.service.BlogService;
import java.io.IOException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final BlogService service;

    public PostController(BlogService service) {
        this.service = service;
    }

    @GetMapping
    public PostPage list(@RequestParam String search, @RequestParam int pageNumber, @RequestParam int pageSize) {
        return service.list(search, pageNumber, pageSize);
    }

    // В ТЗ чтение указано как POST; клиент использует GET, соответствующий семантике чтения HTTP.
    @GetMapping("/{id}")
    public Post get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Post create(@RequestBody PostRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public Post update(@PathVariable long id, @RequestBody PostRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/likes")
    public long like(@PathVariable long id) {
        return service.like(id);
    }

    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void updateImage(@PathVariable long id, @RequestParam("image") MultipartFile image) throws IOException {
        service.updateImage(id, image.getBytes(), image.getContentType());
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        var image = service.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache()).body(image.bytes());
    }

    @GetMapping("/{postId}/comments")
    public List<Comment> comments(@PathVariable long postId) {
        return service.comments(postId);
    }

    @GetMapping("/{postId}/comments/{id}")
    public Comment comment(@PathVariable long postId, @PathVariable long id) {
        return service.comment(postId, id);
    }

    @PostMapping("/{postId}/comments")
    public Comment addComment(@PathVariable long postId, @RequestBody CommentRequest request) {
        return service.addComment(postId, request);
    }

    @PutMapping("/{postId}/comments/{id}")
    public Comment updateComment(@PathVariable long postId, @PathVariable long id,
                                 @RequestBody CommentRequest request) {
        return service.updateComment(postId, id, request);
    }

    @DeleteMapping("/{postId}/comments/{id}")
    public void deleteComment(@PathVariable long postId, @PathVariable long id) {
        service.deleteComment(postId, id);
    }
}
