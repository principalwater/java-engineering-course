package dev.principalwater.study.users.service;

import dev.principalwater.study.users.model.UserPhoto;
import dev.principalwater.study.users.repository.UserPhotoRepository;
import dev.principalwater.study.users.repository.UserRepository;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class UserPhotoService {
    private static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    private final UserPhotoRepository photos;
    private final UserRepository users;

    public UserPhotoService(UserPhotoRepository photos, UserRepository users) {
        this.photos = photos;
        this.users = users;
    }

    public Mono<Void> savePhoto(Long userId, FilePart file) {
        MediaType contentType = file.headers().getContentType();
        if (contentType == null || !"image".equalsIgnoreCase(contentType.getType())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "An image is required"));
        }
        return DataBufferUtils.join(file.content(), MAX_PHOTO_BYTES)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image is empty")))
                .flatMap(buffer -> {
                    byte[] bytes;
                    try {
                        if (buffer.readableByteCount() == 0) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image is empty"));
                        }
                        bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                    return users.existsById(userId).flatMap(exists -> exists
                            ? photos.upsert(userId, contentType.toString(), bytes).then()
                            : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
                });
    }

    public Mono<UserPhoto> getPhoto(Long userId) { return photos.findById(userId); }
}
