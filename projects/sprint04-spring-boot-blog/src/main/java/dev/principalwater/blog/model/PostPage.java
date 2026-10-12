package dev.principalwater.blog.model;

import java.util.List;

public record PostPage(List<Post> posts, boolean hasPrev, boolean hasNext, long lastPage) {
    public PostPage {
        posts = List.copyOf(posts);
    }
}
