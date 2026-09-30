# -----------------------------------------------------------------------------
# Stage 1: Build the shaded Fat JAR with Maven & OpenJDK 25
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Copy Maven files first to take advantage of Docker layer caching
COPY pom.xml ./
RUN apk add --no-cache maven && mvn dependency:go-offline

# Copy full source and build fat JAR
COPY src ./src
RUN mvn clean package -DskipTests

# -----------------------------------------------------------------------------
# Stage 2: Minimal Production Runtime Image
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create unprivileged user for security
RUN addgroup -S redisgroup && adduser -S redisuser -G redisgroup

# Copy built shaded JAR from builder stage
COPY --from=builder /build/target/redis-java-1.0.0.jar /app/redis-server.jar

# Setup data persistence directory
RUN mkdir -p /app/data && chown -R redisuser:redisgroup /app

USER redisuser

# Environment variables for cloud configuration
ENV REDIS_HOST=0.0.0.0
ENV REDIS_PORT=6379
ENV WEB_PORT=8080
ENV AOF_ENABLED=true
ENV AOF_PATH=/app/data/appendonly.aof

# Expose Redis TCP port (6379) and Web Dashboard port (8080)
EXPOSE 6379 8080

VOLUME ["/app/data"]

ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/redis-server.jar"]
