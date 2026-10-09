package dev.principalwater.study.transform;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@CacheableTask
public abstract class TransformFiles extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getInputDir();

    @Internal public abstract DirectoryProperty getOutputDir();
    @Input public abstract Property<String> getFileType();
    @Input public abstract Property<String> getTransformType();

    @OutputFiles public Provider<List<File>> getTransformedFiles() {
        var outputDir = getOutputDir();
        return getInputDir().zip(getFileType(), (input, type) -> selectedFiles(input.getAsFile(), type).stream()
                .map(file -> outputDir.file(file.getName()).get().getAsFile()).toList());
    }

    @TaskAction public void transform() {
        String mode = getTransformType().get();
        if (!mode.equals("uppercase") && !mode.equals("lowercase"))
            throw new GradleException("transformType должен быть uppercase или lowercase");
        try {
            Path input = getInputDir().get().getAsFile().getCanonicalFile().toPath();
            Path output = getOutputDir().get().getAsFile().getCanonicalFile().toPath();
            if (input.startsWith(output) || output.startsWith(input))
                throw new GradleException("Входной и выходной каталоги не должны пересекаться");
            Files.createDirectories(output);
            for (File source : selectedFiles(input.toFile(), getFileType().get())) {
                String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
                String transformed = mode.equals("uppercase") ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
                writeAtomic(output.resolve(source.getName()), transformed);
            }
        } catch (IOException error) {
            throw new GradleException("Не удалось преобразовать файлы: " + error.getMessage(), error);
        }
    }

    private static List<File> selectedFiles(File directory, String type) {
        if (!type.matches("\\.[A-Za-z0-9]+")) throw new GradleException("fileType должен быть расширением, например .txt");
        File[] selected = directory.listFiles(file -> file.isFile() && file.getName().endsWith(type));
        if (selected == null) throw new GradleException("Не удалось прочитать входной каталог: " + directory);
        return Arrays.stream(selected).sorted().toList();
    }

    private static void writeAtomic(Path output, String content) throws IOException {
        Path temporary = Files.createTempFile(output.getParent(), ".transform-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
