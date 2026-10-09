FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /build
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain bootJar
RUN java -Djarmode=tools -jar build/libs/blog.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
WORKDIR /app
RUN chown 10001:10001 /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "blog.jar"]
