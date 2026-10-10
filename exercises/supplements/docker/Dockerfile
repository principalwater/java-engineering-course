# Java 21 bytecode требует совместимого runtime; Java 11 здесь не подходит.
FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b
WORKDIR /app
COPY target/docker-study.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
