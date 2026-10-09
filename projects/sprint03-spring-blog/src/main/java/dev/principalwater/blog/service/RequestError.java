package dev.principalwater.blog.service;

import dev.principalwater.blog.model.BlogLimits;

public enum RequestError {
    INVALID_SEARCH("Поиск до %d символов, номер страницы от 1, размер от 1 до %d"
            .formatted(BlogLimits.MAX_SEARCH_LENGTH, BlogLimits.MAX_PAGE_SIZE)),
    INVALID_POST("Нужны название до %d символов, текст до %d символов и массив до %d тегов"
            .formatted(BlogLimits.MAX_TITLE_LENGTH, BlogLimits.MAX_TEXT_LENGTH, BlogLimits.MAX_TAG_COUNT)),
    INVALID_TAG("Тег должен содержать от 1 до %d символов без пробелов".formatted(BlogLimits.MAX_TAG_LENGTH)),
    INVALID_COMMENT("Нужен текст комментария до %d символов".formatted(BlogLimits.MAX_TEXT_LENGTH)),
    POST_ID_MISMATCH("Идентификатор поста в URL и теле должен совпадать"),
    COMMENT_ID_MISMATCH("Идентификатор комментария в URL и теле должен совпадать"),
    INVALID_IMAGE_SIZE("Размер изображения должен быть от 1 байта до %d МиБ"
            .formatted(BlogLimits.MAX_IMAGE_MEBIBYTES)),
    UNSUPPORTED_IMAGE_FORMAT("Нужно изображение PNG, JPEG, GIF или BMP"),
    INVALID_IMAGE("Некорректное изображение или разрешение больше %d мегапикселей"
            .formatted(BlogLimits.MAX_IMAGE_MEGAPIXELS)),
    UNREADABLE_IMAGE("Не удалось прочитать изображение"),
    NON_POSITIVE_ID("Идентификатор должен быть положительным"),
    INVALID_REQUEST("Проверьте обязательные поля и формат запроса");

    private final String message;

    RequestError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
