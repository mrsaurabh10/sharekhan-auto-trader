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

EXPOSE 8080 5005 9010
RUN mkdir -p /app/logs /app/dumps

ENTRYPOINT ["java", \
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", \
  "-Dcom.sun.management.jmxremote", \
  "-Dcom.sun.management.jmxremote.port=9010", \
  "-Dcom.sun.management.jmxremote.rmi.port=9010", \
  "-Dcom.sun.management.jmxremote.local.only=false", \
  "-Dcom.sun.management.jmxremote.authenticate=false", \
  "-Dcom.sun.management.jmxremote.ssl=false", \
  "-Djava.rmi.server.hostname=103.6.168.235", \
  "-Xms256m", "-Xmx768m", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/app/dumps", \
  "-Xlog:gc*:file=/app/logs/gc.log:utctime", \
  "-jar", "app.jar"]