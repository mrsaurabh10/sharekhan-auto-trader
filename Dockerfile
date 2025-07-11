# Stage 1: Build the JAR using Maven
FROM maven:3.9.6-eclipse-temurin-21 as builder

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy full source and build the JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Use a slim runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR from builder
COPY --from=builder /app/target/SharekhanOrderAPI-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
