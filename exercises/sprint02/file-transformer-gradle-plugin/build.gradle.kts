plugins { `java-gradle-plugin` }

group = "dev.principalwater.study"
version = "1.0-SNAPSHOT"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

gradlePlugin {
    plugins {
        create("fileTransformer") {
            id = "dev.principalwater.file-transformer"
            implementationClass = "dev.principalwater.study.transform.FileTransformerPlugin"
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.2")
}
tasks.test { useJUnitPlatform() }
