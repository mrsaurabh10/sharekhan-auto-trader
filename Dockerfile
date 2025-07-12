FROM maven:3.9.4-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy source + lib
COPY . .
COPY lib/sharekhan-0.0.1-SNAPSHOT.jar lib/

# Build
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]