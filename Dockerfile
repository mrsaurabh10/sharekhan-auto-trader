# Use a slim Java runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy pre-built JAR file (built locally)
COPY target/SharekhanOrderAPI-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
