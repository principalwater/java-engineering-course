package dev.principalwater.study.mvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FilesService {
    private final Path directory;

    public FilesService(@Value("${files.directory}") String directory) throws IOException {
        this.directory = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize());
    }

    public String upload(MultipartFile file) {
        // Клиентское имя не участвует в пути и не может перезаписать чужой файл.
        String filename = UUID.randomUUID() + ".bin";
        try {
            Path staged = Files.createTempFile(directory, "upload-", ".part");
            try {
                file.transferTo(staged);
                Files.move(staged, directory.resolve(filename));
                return filename;
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить файл", failure);
        }
    }

    public Resource download(String filename) {
        if (!filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное имя файла");
        }
        Path path = directory.resolve(filename);
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден");
        }
        try {
            return new ByteArrayResource(Files.readAllBytes(path));
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось прочитать файл", failure);
        }
    }
}
