# Stage 1: build the jar
FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

# Stage 2: compile to a fully static native binary (musl), so it runs FROM scratch
FROM ghcr.io/graalvm/native-image-community:21-muslib AS native
WORKDIR /build
COPY --from=jar /build/target/app.jar app.jar
RUN native-image --static --libc=musl --no-fallback -jar app.jar -o api

# Stage 3: scratch runtime — the image is just the binary (~15 MB)
FROM scratch
COPY --from=native /build/api /api
EXPOSE 8080
ENTRYPOINT ["/api"]
