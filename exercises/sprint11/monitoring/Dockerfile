FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b
COPY build/libs/orders-monitoring.jar /app/orders.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/orders.jar"]
