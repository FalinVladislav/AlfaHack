FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY docs/openapi.yaml ./docs/openapi.yaml
RUN mvn -B -DskipTests package
FROM build AS verify
RUN mvn -B test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/smart-credit-cabinet-1.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
