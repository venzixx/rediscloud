# ==============================================================
# Redis Cloud Platform - Multi-Stage Docker Build
# ==============================================================

# Build Stage
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Expose Redis Wire Protocol (6379) and Web Studio / REST API (8080)
EXPOSE 6379 8080

# Persistent data directory for AOF, accounts, and databases
VOLUME ["/app/data"]

COPY --from=builder /build/target/redis-java-*.jar /app/redis-java.jar

ENV REDIS_PORT=6379
ENV WEB_PORT=8080

ENTRYPOINT ["java", "-jar", "/app/redis-java.jar"]
