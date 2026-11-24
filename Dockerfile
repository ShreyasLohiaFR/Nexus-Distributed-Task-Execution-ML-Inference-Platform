FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S nexus && adduser -S nexus -G nexus
WORKDIR /app
COPY --from=build /app/target/nexus-0.1.0.jar app.jar
USER nexus
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
