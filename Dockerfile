# Build the sole supported WMS runtime artifact.
FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src/main/ src/main/
RUN mvn --batch-mode --no-transfer-progress -Pwms-runtime -Dmaven.test.skip=true package

# Minimal non-root Java 21 runtime.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/target/zirologistics-0.0.1-SNAPSHOT.jar /app/app.jar

USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
