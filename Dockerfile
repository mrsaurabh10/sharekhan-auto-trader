# STAGE 1: Build JAR
FROM maven:3.9.4-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy project files
COPY . .

# Install Sharekhan JAR to local Maven repo
RUN mvn install:install-file \
    -Dfile=lib/sharekhan-0.0.1-SNAPSHOT.jar \
    -DgroupId=com.sharekhan \
    -DartifactId=sharekhan \
    -Dversion=0.0.1-SNAPSHOT \
    -Dpackaging=jar

# Build your app
RUN mvn clean package -DskipTests

# STAGE 2: Run JAR
FROM mcr.microsoft.com/playwright/java:v1.44.0-jammy

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080 5005
RUN mkdir -p /app/logs /app/dumps

ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-Xms256m", "-Xmx512m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/dumps", "-Xlog:gc*:utctime:file=/app/logs/gc.log:time", "-jar", "app.jar"]
