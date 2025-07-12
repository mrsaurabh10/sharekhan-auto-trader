# ------------ STAGE 1: Build using Maven ------------
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copy the pom file first (for caching dependencies)
COPY pom.xml .

# Manually install the Sharekhan SDK JAR into Maven repo
COPY lib/sharekhan-0.0.1-SNAPSHOT.jar /tmp/sharekhan-sdk.jar
RUN mvn install:install-file \
    -Dfile=/tmp/sharekhan-sdk.jar \
    -DgroupId=com.sharekhan \
    -DartifactId=sharekhan \
    -Dversion=0.0.1-SNAPSHOT \
    -Dpackaging=jar

# Copy the rest of the source code
COPY src /app/src

# Build the application
RUN mvn clean package -DskipTests

# ------------ STAGE 2: Run with slim JRE ------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port (optional)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]