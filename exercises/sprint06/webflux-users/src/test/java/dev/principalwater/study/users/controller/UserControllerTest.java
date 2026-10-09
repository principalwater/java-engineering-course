package dev.principalwater.study.users.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.principalwater.study.users.model.User;
import dev.principalwater.study.users.model.UserPhoto;
import dev.principalwater.study.users.service.UserPhotoService;
import dev.principalwater.study.users.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(UserController.class)
class UserControllerTest {
    @Autowired private WebTestClient http;
    @MockitoBean private UserService users;
    @MockitoBean private UserPhotoService photos;

    /** Срез проверяет модель и HTML с контролируемым источником; интеграция отдельно проверяет реальную БД. */
    @Test
    void listRendersTheSuppliedUsers() {
        when(users.findAll("")).thenReturn(Flux.just(new User(1L, "Иван", "Иванов", 30, true),
                new User(2L, "Мария", "Сидорова", 28, true)));
        http.get().uri("/users").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class).value(html -> assertThat(html).contains("<h1>Пользователи</h1>", "Иванов", "Сидорова"));
    }

    @Test
    void absentPhotoReturnsPngPlaceholder() {
        when(photos.getPhoto(1L)).thenReturn(Mono.empty());
        http.get().uri("/users/1/photo").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).value(bytes -> assertThat(bytes).isNotEmpty().startsWith(new byte[]{(byte) 137, 80, 78, 71}));
    }

    @Test
    void storedPhotoPreservesBytesAndContentType() {
        byte[] data = {1, 2, 3};
        when(photos.getPhoto(2L)).thenReturn(Mono.just(new UserPhoto(2L, "image/jpeg", data)));
        http.get().uri("/users/2/photo").exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_JPEG).expectBody(byte[].class).isEqualTo(data);
    }
}
