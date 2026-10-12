package dev.principalwater.blog.model;

public final class BlogLimits {
    public static final int MAX_SEARCH_LENGTH = 1_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_TITLE_LENGTH = 500;
    public static final int MAX_TEXT_LENGTH = 1_000_000;
    public static final int MAX_TAG_COUNT = 50;
    public static final int MAX_TAG_LENGTH = 100;
    public static final int MAX_IMAGE_MEBIBYTES = 5;
    public static final int MAX_IMAGE_BYTES = MAX_IMAGE_MEBIBYTES * 1024 * 1024;
    public static final int MAX_IMAGE_MEGAPIXELS = 16;
    public static final int MAX_IMAGE_PIXELS = MAX_IMAGE_MEGAPIXELS * 1_000_000;

    private BlogLimits() {
    }
}
