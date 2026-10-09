package dev.principalwater.study.transform;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileTransformerPluginTest {
    @TempDir Path project;

    @ParameterizedTest
    @CsvSource({"uppercase, HELLO ПРИВЕТ JAVA", "lowercase, hello привет java"})
    void transformsConfiguredFilesAndCountsOnlyTheirOutputs(String mode, String expected) throws Exception {
        configure(mode, "outputs");
        Files.writeString(project.resolve("inputs/message.txt"), "Hello Привет Java");
        Files.writeString(project.resolve("inputs/ignored.json"), "ignore");
        Files.createDirectories(project.resolve("outputs"));
        Files.writeString(project.resolve("outputs/unrelated.txt"), "this file is not a transformation result");
        var result = runner("countWords").build();
        assertEquals(expected, Files.readString(project.resolve("outputs/message.txt")));
        assertFalse(Files.exists(project.resolve("outputs/ignored.json")));
        assertTrue(result.getOutput().contains("Word count: 3"));
        assertEquals(TaskOutcome.SUCCESS, result.task(":transformFiles").getOutcome());
    }

    @Test void reusesUnchangedOutputsAndDoesNotCountOrphansAfterInputRemoval() throws Exception {
        configure("uppercase", "outputs");
        Files.writeString(project.resolve("inputs/message.txt"), "one two");
        runner("countWords").build();
        var unchanged = runner("countWords").build();
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":transformFiles").getOutcome());
        Files.delete(project.resolve("inputs/message.txt"));
        var empty = runner("countWords").build();
        assertTrue(empty.getOutput().contains("Word count: 0"));
        assertEquals("ONE TWO", Files.readString(project.resolve("outputs/message.txt")));
    }

    @Test void refusesOutputInsideInputBeforeChangingOriginalFiles() throws Exception {
        configure("uppercase", "inputs/results");
        Files.writeString(project.resolve("inputs/message.txt"), "unchanged");
        var failed = runner("transformFiles").buildAndFail();
        assertTrue(failed.getOutput().contains("Входной и выходной каталоги не должны пересекаться"));
        assertEquals("unchanged", Files.readString(project.resolve("inputs/message.txt")));
    }

    @ParameterizedTest
    @CsvSource({"unknown, .txt, transformType", "uppercase, txt, fileType", "uppercase, .txt, missing"})
    void rejectsInvalidParametersBeforeWritingOutputs(String mode, String type, String expected) throws Exception {
        configure(mode, "outputs");
        Path build = project.resolve("build.gradle");
        Files.writeString(build, Files.readString(build) + "\nfileTransformer.fileType.set('" + type + "')\n");
        if (expected.equals("missing")) Files.delete(project.resolve("inputs"));
        var failed = runner("transformFiles").buildAndFail();
        if (expected.equals("missing")) {
            assertTrue(failed.getOutput().contains("inputs"));
        } else {
            assertTrue(failed.getOutput().contains(expected));
        }
        assertFalse(Files.exists(project.resolve("outputs")));
    }

    private void configure(String mode, String output) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'dev.principalwater.file-transformer' }
                fileTransformer {
                    inputDir = layout.projectDirectory.dir('inputs')
                    outputDir = layout.projectDirectory.dir('%s')
                    fileType = '.txt'
                    transformType = '%s'
                }
                """.formatted(output, mode));
        Files.createDirectories(project.resolve("inputs"));
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(tasks);
    }
}
