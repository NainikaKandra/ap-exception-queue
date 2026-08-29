# ── Stage 1: compile ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy only source files needed for compilation
COPY src ./src

# Compile all Java files into /app/out
RUN mkdir -p out && find src -name "*.java" | xargs javac -d out

# ── Stage 2: run ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy compiled bytecode from build stage
COPY --from=build /app/out ./out

# Copy runtime assets (CSV data + HTML dashboard)
COPY data       ./data
COPY resources  ./resources

# Railway / Render inject $PORT at runtime; Server.java reads it.
# Expose a default for local docker run -p 8080:8080 convenience.
EXPOSE 8080

CMD ["java", "-cp", "out", "com.example.problem9.Server"]
