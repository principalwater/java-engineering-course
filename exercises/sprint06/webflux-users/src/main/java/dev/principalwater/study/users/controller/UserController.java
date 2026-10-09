package dev.principalwater.study.users.controller;

import dev.principalwater.study.users.model.UserForm;
import dev.principalwater.study.users.service.UserPhotoService;
import dev.principalwater.study.users.service.UserService;
import jakarta.validation.Valid;
import java.util.Base64;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.reactive.result.view.Rendering;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/users")
public class UserController {
    private static final byte[] PNG_PLACEHOLDER = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMB/axu2kQAAAAASUVORK5CYII=");
    private final UserService users;
    private final UserPhotoService photos;

    public UserController(UserService users, UserPhotoService photos) {
        this.users = users;
        this.photos = photos;
    }

    @GetMapping
    public Mono<Rendering> list(@RequestParam(defaultValue = "") String lastName) {
        return Mono.just(Rendering.view("users/list").modelAttribute("users", users.findAll(lastName))
                .modelAttribute("lastName", lastName).build());
    }

    @GetMapping("/{id}")
    public Mono<Rendering> view(@PathVariable Long id) {
        return users.findById(id).map(user -> Rendering.view("users/view").modelAttribute("user", user).build())
                .defaultIfEmpty(Rendering.redirectTo("/users").build());
    }

    @GetMapping("/new")
    public Mono<Rendering> createForm() {
        return Mono.just(Rendering.view("users/form").modelAttribute("user", new UserForm())
                .modelAttribute("action", "/users").build());
    }

    @GetMapping("/{id}/edit")
    public Mono<Rendering> editForm(@PathVariable Long id) {
        return users.findById(id).map(user -> Rendering.view("users/form")
                .modelAttribute("user", UserForm.from(user)).modelAttribute("action", "/users/" + id)
                .modelAttribute("userId", id).build()).defaultIfEmpty(Rendering.redirectTo("/users").build());
    }

    @PostMapping
    public Mono<String> create(@Valid @ModelAttribute("user") UserForm form, BindingResult errors,
                               Model model, ServerWebExchange exchange) {
        model.addAttribute("action", "/users");
        if (errors.hasErrors()) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.just("users/form");
        }
        return users.create(form).map(user -> "redirect:/users/" + user.id());
    }

    @PostMapping("/{id}")
    public Mono<String> update(@PathVariable Long id, @Valid @ModelAttribute("user") UserForm form,
                               BindingResult errors, Model model, ServerWebExchange exchange) {
        model.addAttribute("action", "/users/" + id).addAttribute("userId", id);
        if (errors.hasErrors()) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.just("users/form");
        }
        return users.update(id, form).map(user -> "redirect:/users/" + user.id());
    }

    @PostMapping("/{id}/delete")
    public Mono<String> delete(@PathVariable Long id) { return users.delete(id).thenReturn("redirect:/users"); }

    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<String> uploadPhoto(@PathVariable Long id, @RequestPart("photo") FilePart photo) {
        return photos.savePhoto(id, photo).thenReturn("redirect:/users/" + id);
    }

    @GetMapping("/{id}/photo")
    public Mono<ResponseEntity<byte[]>> getPhoto(@PathVariable Long id) {
        return photos.getPhoto(id).map(photo -> photoResponse(photo.data(), imageType(photo.contentType())))
                .defaultIfEmpty(photoResponse(PNG_PLACEHOLDER, MediaType.IMAGE_PNG));
    }

    private MediaType imageType(String value) {
        try {
            MediaType type = MediaType.parseMediaType(value);
            return "image".equalsIgnoreCase(type.getType()) ? type : MediaType.IMAGE_PNG;
        } catch (IllegalArgumentException exception) {
            return MediaType.IMAGE_PNG;
        }
    }

    private ResponseEntity<byte[]> photoResponse(byte[] data, MediaType contentType) {
        // image/* допускает SVG; sandbox и nosniff исключают исполнение активного содержимого фото.
        return ResponseEntity.ok().contentType(contentType).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox").body(data);
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Void> photoTooLarge() { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build(); }
}
