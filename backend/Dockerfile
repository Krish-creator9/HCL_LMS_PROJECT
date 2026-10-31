# Stage 1: Build Spring Boot JAR with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml ./
COPY .mvn .mvn/
COPY mvnw ./
RUN mvn dependency:go-offline -B || true

# Copy source code and package application
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal, secure JRE runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create unprivileged application user for container security
RUN addgroup -S ordercraft && adduser -S ordercraft -G ordercraft
USER ordercraft:ordercraft

# Copy compiled JAR from build stage
COPY --from=build /app/target/*.jar app.jar

ENV PORT=8081
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
