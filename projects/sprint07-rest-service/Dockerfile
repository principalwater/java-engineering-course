FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5 AS layers
ARG JAR_FILE=build/deployment/storefront.jar
WORKDIR /extract
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
WORKDIR /app
RUN chown 10001:10001 /app
COPY --from=layers /extract/extracted/dependencies/ ./
COPY --from=layers /extract/extracted/spring-boot-loader/ ./
COPY --from=layers /extract/extracted/snapshot-dependencies/ ./
COPY --from=layers /extract/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# Heap — часть бюджета: Netty, metaspace, стеки и JIT требуют отдельного запаса вне heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40.0", "-XX:MaxDirectMemorySize=48m", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
