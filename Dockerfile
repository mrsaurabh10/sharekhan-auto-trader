# Stage 1: Build
FROM maven:3.9.4-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy everything to container
COPY . .

# Install Sharekhan JAR to local Maven repo
RUN if [ -f lib/sharekhan-0.0.1-SNAPSHOT.jar ]; then \
    mvn install:install-file \
      -Dfile=lib/sharekhan-0.0.1-SNAPSHOT.jar \
      -DgroupId=com.sharekhan \
      -DartifactId=sharekhan \
      -Dversion=0.0.1-SNAPSHOT \
      -Dpackaging=jar; \
    else \
    echo "❌ Sharekhan SDK JAR not found at lib/sharekhan-0.0.1-SNAPSHOT.jar" && exit 1; \
    fi

# Package the Spring Boot application
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]