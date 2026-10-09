plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.principalwater.study"
version = "1.0-SNAPSHOT"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("io.r2dbc:r2dbc-h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
tasks.test { useJUnitPlatform() }
tasks.bootJar { archiveFileName.set("webflux-users.jar") }
