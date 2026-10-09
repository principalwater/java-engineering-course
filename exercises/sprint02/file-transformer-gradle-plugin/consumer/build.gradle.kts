plugins { id("dev.principalwater.file-transformer") }
fileTransformer {
    inputDir.set(layout.projectDirectory.dir("input"))
    outputDir.set(layout.buildDirectory.dir("processed"))
    fileType.set(".txt")
    transformType.set("uppercase")
}
