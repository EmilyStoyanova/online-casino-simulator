# syntax=docker/dockerfile:1
#
# Backend image (Render staging, any container host). Build context: backend/.
#     docker build -t casino-backend backend
# The image contains no configuration values: database, secrets and profiles come from the
# environment at runtime (infrastructure/render/README.md).

# --- Build: Maven via the wrapper (pinned Maven version and checksum) ---------------------------
FROM eclipse-temurin:21.0.13_9-jdk-alpine-3.24@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3 AS build
WORKDIR /workspace

# Wrapper and POM first, so the dependency layer is reused while only sources change.
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN sh mvnw -B -ntp -q dependency:go-offline

COPY src/ src/
# Tests need Docker (Testcontainers) and run in CI; the image build only packages.
RUN sh mvnw -B -ntp -DskipTests package \
    && cp target/casino-simulator-backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- Runtime: JRE only, non-root -------------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474

# Fixed numeric ids, so platforms can verify that the process does not run as root.
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app

# Extracted jar (app.jar + lib/, faster start than the nested jar), in layers from the least to
# the most frequently changed. Owned by root: the application user can read but not modify it.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001

# Sized for a 512 MB instance (see the README's memory notes); override JDK_JAVA_OPTIONS to change.
# - heap at most 60 % of the container limit, the rest for metaspace, threads and code cache;
# - Serial GC: lowest overhead on a fraction of one CPU;
# - C1 only (TieredStopAtLevel=1): less CPU spent compiling, faster start on a small CPU share;
# - exit on OutOfMemoryError, so the platform restarts the instance instead of leaving it broken.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"

# Documentation only: the port comes from PORT (profile `render`) or SERVER_PORT, default 8080.
EXPOSE 8080

# Exec form: Java is PID 1 and receives SIGTERM directly (graceful shutdown).
ENTRYPOINT ["java", "-jar", "app.jar"]
