package dev.principalwater.blog.model;

public record CommentRequest(Long id, String text, Long postId) {
}
