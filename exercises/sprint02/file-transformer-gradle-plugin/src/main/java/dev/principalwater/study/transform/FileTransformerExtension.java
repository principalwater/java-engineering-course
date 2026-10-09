package dev.principalwater.study.transform;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

public abstract class FileTransformerExtension {
    public abstract DirectoryProperty getInputDir();
    public abstract DirectoryProperty getOutputDir();
    public abstract Property<String> getFileType();
    public abstract Property<String> getTransformType();
}
