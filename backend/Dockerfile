FROM maven:3.9-eclipse-temurin-21 AS development
WORKDIR /app
COPY pom.xml ./
COPY . ./
EXPOSE 8080
CMD ["mvn", "-B", "-DskipTests", "spring-boot:run"]

FROM development AS build
WORKDIR /app
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre AS production
WORKDIR /app
COPY --from=build /app/target/smart-credit-cabinet-1.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
