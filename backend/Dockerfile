FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 relay
COPY --from=build /workspace/target/relay-backend-1.0.0.jar /app/relay.jar
USER relay
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/relay.jar"]
