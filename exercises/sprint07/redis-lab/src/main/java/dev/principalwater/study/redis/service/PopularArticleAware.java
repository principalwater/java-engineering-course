package dev.principalwater.study.redis.service;

public interface PopularArticleAware {
    /** Сохраняет статью на сутки с последующим продлением при чтении. */
    void cache(String isbnNumber, String articleContent);

    /** Возвращает статью и продлевает TTL; при отсутствии возвращает null. */
    String getArticle(String isbnNumber);
}
