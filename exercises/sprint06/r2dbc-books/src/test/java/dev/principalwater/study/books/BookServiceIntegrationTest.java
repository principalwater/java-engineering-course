package dev.principalwater.study.books;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.test.StepVerifier;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class BookServiceIntegrationTest {
    @Autowired private BookService service;
    @Autowired private BookRepository books;
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    /** Пустое завершение не заменяет созданную книгу: результат содержит ID и читается из настоящей БД. */
    @Test
    void createBookReturnsPersistedTitleAndGeneratedId() {
        Book saved = service.createBook("Java: реактивный доступ").block(VERIFY_TIMEOUT);
        assertThat(saved).isNotNull();
        try {
            assertThat(saved.id()).isPositive();
            assertThat(saved.title()).isEqualTo("Java: реактивный доступ");
            assertThat(books.findById(saved.id()).block(VERIFY_TIMEOUT)).isEqualTo(saved);
        } finally {
            books.deleteById(saved.id()).block(VERIFY_TIMEOUT);
        }
    }

    /** Проверяется полный путь Service → reactive repository → H2 и сохранность соседней записи. */
    @Test
    void saveDeleteChainCompletesAndPreservesExistingBook(CapturedOutput output) {
        Book existing = books.save(new Book(null, "Existing book")).block(VERIFY_TIMEOUT);
        try {
            StepVerifier.create(service.saveAndDeleteBook("O'Коннор: Java"))
                    .expectComplete().verify(VERIFY_TIMEOUT);
            assertThat(output.getOut()).containsPattern("Saved book ID: [1-9][0-9]*")
                    .contains("Book exists after deletion: false");
            for (String invalid : new String[]{null, " "}) {
                StepVerifier.create(service.saveAndDeleteBook(invalid))
                        .expectError(IllegalArgumentException.class).verify(VERIFY_TIMEOUT);
            }
            assertThat(books.findAll().collectList().block(VERIFY_TIMEOUT)).isEqualTo(List.of(existing));
        } finally {
            books.deleteById(existing.id()).block(VERIFY_TIMEOUT);
        }
    }
}
