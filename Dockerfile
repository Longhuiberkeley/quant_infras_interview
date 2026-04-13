# Stage 1 — Build
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2 — Run
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --create-home --shell /bin/bash appuser
COPY --from=build /build/target/binance-quote-service-0.1.0-SNAPSHOT.jar app.jar
USER appuser
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
