package dev.principalwater.blog.service;

import dev.principalwater.blog.model.BlogLimits;

public enum RequestError {
    INVALID_SEARCH("Search must not exceed %d characters; page number must be positive and page size between 1 and %d"
            .formatted(BlogLimits.MAX_SEARCH_LENGTH, BlogLimits.MAX_PAGE_SIZE)),
    INVALID_POST("Title up to %d characters, text up to %d characters and an array of up to %d tags are required"
            .formatted(BlogLimits.MAX_TITLE_LENGTH, BlogLimits.MAX_TEXT_LENGTH, BlogLimits.MAX_TAG_COUNT)),
    INVALID_TAG("Tag must contain between 1 and %d characters without whitespace".formatted(BlogLimits.MAX_TAG_LENGTH)),
    INVALID_COMMENT("Comment text up to %d characters is required".formatted(BlogLimits.MAX_TEXT_LENGTH)),
    POST_ID_MISMATCH("Post ID in the URL and body must match"),
    COMMENT_ID_MISMATCH("Comment ID in the URL and body must match"),
    INVALID_IMAGE_SIZE("Image size must be between 1 byte and %d MiB"
            .formatted(BlogLimits.MAX_IMAGE_MEBIBYTES)),
    UNSUPPORTED_IMAGE_FORMAT("PNG, JPEG, GIF or BMP image is required"),
    INVALID_IMAGE("Image is invalid or exceeds %d megapixels"
            .formatted(BlogLimits.MAX_IMAGE_MEGAPIXELS)),
    UNREADABLE_IMAGE("Image could not be read"),
    NON_POSITIVE_ID("ID must be positive"),
    INVALID_REQUEST("Required fields or request format are invalid");

    private final String message;

    RequestError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
