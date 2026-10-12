package dev.principalwater.blog.model;

import java.util.List;

public record Post(Long id, String title, String text, List<String> tags,
                   long likesCount, long commentsCount) {
    private static final int PREVIEW_CODE_POINT_LIMIT = 128;
    private static final String PREVIEW_ELLIPSIS = "…";

    public Post {
        tags = List.copyOf(tags);
    }

    public Post preview() {
        if (text.codePointCount(0, text.length()) <= PREVIEW_CODE_POINT_LIMIT) {
            return this;
        }
        return new Post(id, title, text.substring(0, text.offsetByCodePoints(0, PREVIEW_CODE_POINT_LIMIT))
                + PREVIEW_ELLIPSIS,
                tags, likesCount, commentsCount);
    }
}
