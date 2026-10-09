package dev.principalwater.study.transform;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

public abstract class CountWords extends DefaultTask {
    private static final Pattern WORD = Pattern.compile("(?U)\\S+");
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProcessedFiles();

    @TaskAction public void count() {
        long count = 0;
        try {
            for (File file : getProcessedFiles()) {
                count += WORD.matcher(Files.readString(file.toPath(), StandardCharsets.UTF_8)).results().count();
            }
        } catch (IOException error) {
            throw new GradleException("Не удалось подсчитать слова: " + error.getMessage(), error);
        }
        getLogger().lifecycle("Word count: {}", count);
    }
}
