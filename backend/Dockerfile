FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY offerpilot-platform/pom.xml offerpilot-platform/pom.xml
COPY offerpilot-agent/pom.xml offerpilot-agent/pom.xml
COPY offerpilot-identity/pom.xml offerpilot-identity/pom.xml
COPY offerpilot-resume/pom.xml offerpilot-resume/pom.xml
COPY offerpilot-job/pom.xml offerpilot-job/pom.xml
COPY offerpilot-career/pom.xml offerpilot-career/pom.xml
COPY offerpilot-interview/pom.xml offerpilot-interview/pom.xml
COPY offerpilot-bootstrap/pom.xml offerpilot-bootstrap/pom.xml
RUN mvn -q -B -pl offerpilot-bootstrap -am dependency:go-offline
COPY offerpilot-platform offerpilot-platform
COPY offerpilot-agent offerpilot-agent
COPY offerpilot-identity offerpilot-identity
COPY offerpilot-resume offerpilot-resume
COPY offerpilot-job offerpilot-job
COPY offerpilot-career offerpilot-career
COPY offerpilot-interview offerpilot-interview
COPY offerpilot-bootstrap offerpilot-bootstrap
RUN mvn -q -B -pl offerpilot-bootstrap -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/offerpilot-bootstrap/target/*.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
