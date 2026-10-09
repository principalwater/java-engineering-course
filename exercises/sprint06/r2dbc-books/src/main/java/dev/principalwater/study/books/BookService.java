package dev.principalwater.study.books;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class BookService {
    private static final Logger LOG = LoggerFactory.getLogger(BookService.class);
    private final BookRepository books;

    public BookService(BookRepository books) {
        this.books = books;
    }

    public Mono<Void> saveAndDeleteBook(String name) {
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("Book title must not be blank"));
        }
        return books.save(new Book(null, name))
                .doOnNext(saved -> LOG.info("Saved book ID: {}", saved.id()))
                .flatMap(saved -> books.deleteById(saved.id()).then(books.existsById(saved.id())))
                .flatMap(exists -> {
                    LOG.info("Book exists after deletion: {}", exists);
                    return exists ? Mono.error(new IllegalStateException("Book was not deleted")) : Mono.empty();
                });
    }
}
