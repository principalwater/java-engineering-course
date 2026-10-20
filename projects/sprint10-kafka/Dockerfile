FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5 AS layers
ARG SERVICE
WORKDIR /extract
COPY build/deployment/${SERVICE}.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
WORKDIR /app
COPY --from=layers /extract/extracted/dependencies/ ./
COPY --from=layers /extract/extracted/spring-boot-loader/ ./
COPY --from=layers /extract/extracted/snapshot-dependencies/ ./
COPY --from=layers /extract/extracted/application/ ./
COPY config/jvm-network.security /app/network.security
COPY config/entrypoint.sh /app/entrypoint.sh
USER 10001:10001
EXPOSE 8080
# Heap оставляет запас для metaspace, JIT, виртуальных потоков и Netty Gateway.
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
