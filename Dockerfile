FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:resolve-plugins \
        org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/report-generator-0.1.0-SNAPSHOT.jar ./app.jar
VOLUME ["/app/input", "/app/output"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["/app/input/config.json", "/app/input/data.xlsx", "/app/output/report.html"]
