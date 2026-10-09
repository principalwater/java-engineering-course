package dev.principalwater.blog.model;

public enum BlogEntity {
    POST("Пост", "не найден"),
    IMAGE("Изображение", "не найдено"),
    COMMENT("Комментарий", "не найден");

    private final String russianName;
    private final String notFoundMessage;

    BlogEntity(String russianName, String notFoundSuffix) {
        this.russianName = russianName;
        this.notFoundMessage = russianName + " " + notFoundSuffix;
    }

    public String russianName() {
        return russianName;
    }

    public String notFoundMessage() {
        return notFoundMessage;
    }
}
