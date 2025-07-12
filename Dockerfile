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
FROM eclipse-temurin:21-jdk

# ✅ Install Playwright browser dependencies
RUN apt-get update && \
    apt-get install -y wget gnupg ca-certificates curl && \
    apt-get install -y libatk-bridge2.0-0 libgtk-3-0 libxss1 libasound2 libnss3 libxcomposite1 libxrandr2 libgbm1 libxdamage1 libxfixes3 libxext6 libx11-xcb1 fonts-liberation libdrm2 libx11-6 libx11-data libfontconfig1 && \
    apt-get clean
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]