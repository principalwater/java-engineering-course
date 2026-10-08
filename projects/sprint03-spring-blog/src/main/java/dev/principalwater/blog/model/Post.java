package dev.principalwater.blog.model;

import java.util.List;

public record Post(Long id, String title, String text, List<String> tags,
                   long likesCount, long commentsCount) {
    public Post {
        tags = List.copyOf(tags);
    }

    public Post preview() {
        if (text.codePointCount(0, text.length()) <= 128) {
            return this;
        }
        return new Post(id, title, text.substring(0, text.offsetByCodePoints(0, 128)) + "…",
                tags, likesCount, commentsCount);
    }
}
