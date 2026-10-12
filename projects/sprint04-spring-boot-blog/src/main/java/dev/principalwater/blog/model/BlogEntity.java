package dev.principalwater.blog.model;

public enum BlogEntity {
    POST("Post"),
    IMAGE("Image"),
    COMMENT("Comment");

    private final String notFoundMessage;

    BlogEntity(String name) {
        this.notFoundMessage = name + " not found";
    }

    public String notFoundMessage() {
        return notFoundMessage;
    }
}
