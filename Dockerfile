# ── Stage 1: compile ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy source files
COPY src ./src

# Find all .java files, write to a file list, then compile
RUN mkdir -p out && \
    find src -name "*.java" > sources.txt && \
    javac -d out @sources.txt && \
    echo "Compiled OK"

# ── Stage 2: run ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy compiled bytecode
COPY --from=build /app/out ./out

# Copy runtime assets
COPY data       ./data
COPY resources  ./resources

# Railway injects PORT at runtime; Server.java reads it with fallback to 8080
EXPOSE 8080

ENTRYPOINT ["java", "-cp", "out", "com.example.problem9.Server"]
