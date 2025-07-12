# Stage 1: Build
FROM maven:3.9.4-eclipse-temurin-21 as builder
WORKDIR /app

# Copy full project
COPY . .

# Copy the Sharekhan JAR into the correct .m2 location
RUN mkdir -p /root/.m2/repository/com/sharekhan/sharekhan/0.0.1-SNAPSHOT/ && \
    cp lib/sharekhan-0.0.1-SNAPSHOT.jar /root/.m2/repository/com/sharekhan/sharekhan/0.0.1-SNAPSHOT/sharekhan-0.0.1-SNAPSHOT.jar

# Also install it to local maven repo (cleaner way)
RUN mvn install:install-file \
    -Dfile=lib/sharekhan-0.0.1-SNAPSHOT.jar \
    -DgroupId=com.sharekhan \
    -DartifactId=sharekhan \
    -Dversion=0.0.1-SNAPSHOT \
    -Dpackaging=jar

# Then build the actual app
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=builder /app/target/SharekhanOrderAPI-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]