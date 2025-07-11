# Use the Eclipse Temurin JDK 21 as base image
FROM eclipse-temurin:21-jdk

# Create a working directory
WORKDIR /app

# Copy your built JAR (adjust the filename if needed)
COPY target/SharekhanOrderAPI-1.0-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot app listens on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
