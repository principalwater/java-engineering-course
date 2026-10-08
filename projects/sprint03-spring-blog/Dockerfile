FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM tomcat:10.1-jdk21-temurin@sha256:887953afba0434879427f18d011d4df26d49e1f5c9aad18b779ed738edf22cf3
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* /usr/local/tomcat/webapps/*
COPY --from=build /build/target/blog.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
