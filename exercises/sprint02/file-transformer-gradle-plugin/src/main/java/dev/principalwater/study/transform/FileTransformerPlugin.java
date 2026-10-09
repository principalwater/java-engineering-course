package dev.principalwater.study.transform;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class FileTransformerPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        var extension = project.getExtensions().create("fileTransformer", FileTransformerExtension.class);
        extension.getInputDir().convention(project.getLayout().getProjectDirectory().dir("input"));
        extension.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("transformed"));
        extension.getFileType().convention(".txt");
        extension.getTransformType().convention("uppercase");

        var transform = project.getTasks().register("transformFiles", TransformFiles.class, task -> {
            task.setGroup("files");
            task.setDescription("Преобразовать выбранные текстовые файлы.");
            task.getInputDir().set(extension.getInputDir());
            task.getOutputDir().set(extension.getOutputDir());
            task.getFileType().set(extension.getFileType());
            task.getTransformType().set(extension.getTransformType());
        });
        project.getTasks().register("countWords", CountWords.class, task -> {
            task.setGroup("files");
            task.setDescription("Подсчитать слова в результатах текущего преобразования.");
            // Передача outputs обеспечивает зависимость от transformFiles без второго dependsOn.
            task.getProcessedFiles().from(transform);
        });
    }
}
