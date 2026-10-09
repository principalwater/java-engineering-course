package dev.principalwater.study.users;

import static org.assertj.core.api.Assertions.assertThat;

import dev.principalwater.study.users.repository.UserPhotoRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class UserFlowIntegrationTest {
    @Autowired private WebTestClient http;
    @Autowired private UserPhotoRepository photos;

    /** Настоящий HTTP, Thymeleaf и H2 проверяют формы, ID, фото, ограничения и каскадное удаление. */
    @Test
    void userAndPhotoRoundTripPreservesTheDatabaseAndRenderedValues() throws Exception {
        http.get().uri("/users").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(html -> assertThat(html).contains("<h1>Пользователи</h1>", "Иван", "Мария"));
        http.get().uri("/users/new").exchange().expectStatus().isOk()
                .expectBody(String.class).value(html -> assertThat(html).contains("name=\"firstName\"", "name=\"age\""));
        String location = http.post().uri("/users").body(BodyInserters.fromFormData("firstName", "O'Коннор <script>test</script>")
                        .with("lastName", "Тест").with("age", "34").with("active", "true").with("id", "1"))
                .exchange().expectStatus().is3xxRedirection().expectBody().returnResult()
                .getResponseHeaders().getFirst("Location");
        assertThat(location).matches("/users/[0-9]+");
        long id = Long.parseLong(location.substring("/users/".length()));
        assertThat(id).isGreaterThan(3L);
        try {
            http.get().uri(location).exchange().expectStatus().isOk().expectBody(String.class)
                    .value(html -> assertThat(html).contains("&lt;script&gt;test&lt;/script&gt;", "34")
                            .doesNotContain("<script>test</script>"));
            http.get().uri("/users?lastName=тЕсТ").exchange().expectStatus().isOk().expectBody(String.class)
                    .value(html -> assertThat(html).contains("Коннор").doesNotContain("Сидорова"));
            http.get().uri(location + "/photo").exchange().expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.IMAGE_PNG)
                    .expectBody(byte[].class).value(bytes -> assertThat(bytes).isNotEmpty().startsWith(new byte[]{(byte) 137, 80, 78, 71}));

            byte[] original = png(0x168b87);
            upload(location, original, MediaType.IMAGE_PNG).expectStatus().is3xxRedirection();
            http.get().uri(location + "/photo").exchange().expectStatus().isOk()
                    .expectHeader().contentType(MediaType.IMAGE_PNG)
                    .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                    .expectBody(byte[].class).isEqualTo(original);
            byte[] replacement = png(0x2f65c8);
            upload(location, replacement, MediaType.IMAGE_PNG).expectStatus().is3xxRedirection();
            http.get().uri(location + "/photo").exchange().expectBody(byte[].class).isEqualTo(replacement);
            upload(location, new byte[]{1}, MediaType.TEXT_PLAIN).expectStatus().isEqualTo(415);
            upload(location, new byte[0], MediaType.IMAGE_PNG).expectStatus().isBadRequest();
            upload(location, new byte[5 * 1024 * 1024 + 1], MediaType.IMAGE_PNG).expectStatus().isEqualTo(413);
            http.get().uri(location + "/photo").exchange().expectBody(byte[].class).isEqualTo(replacement);

            http.get().uri(location + "/edit").exchange().expectStatus().isOk()
                    .expectBody(String.class).value(html -> assertThat(html).contains("Форма пользователя"));
            http.post().uri(location).body(BodyInserters.fromFormData("firstName", "Обновлённый")
                            .with("lastName", "Тест").with("age", "-1"))
                    .exchange().expectStatus().isBadRequest();
            http.post().uri(location).body(BodyInserters.fromFormData("firstName", "Обновлённый")
                            .with("lastName", "Тест").with("age", "35"))
                    .exchange().expectStatus().is3xxRedirection();
            http.get().uri(location).exchange().expectBody(String.class)
                    .value(html -> assertThat(html).contains("Обновлённый", "35", "Неактивен"));
            http.post().uri(location + "/delete").exchange().expectStatus().is3xxRedirection();
            http.get().uri(location).exchange().expectStatus().is3xxRedirection();
            assertThat(photos.existsById(id).block(Duration.ofSeconds(5))).isFalse();
            http.get().uri("/users/1").exchange().expectStatus().isOk().expectBody(String.class)
                    .value(html -> assertThat(html).contains("Иванов"));
        } finally {
            http.post().uri(location + "/delete").exchange();
        }
    }

    private WebTestClient.ResponseSpec upload(String location, byte[] data, MediaType contentType) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("photo", new ByteArrayResource(data) {
            @Override public String getFilename() { return "fixture.png"; }
        }).contentType(contentType);
        return http.post().uri(location + "/photo").body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    private static byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
