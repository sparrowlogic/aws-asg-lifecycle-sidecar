FROM maven:3-amazoncorretto-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn package -DskipTests -B && \
    mv target/aws-asg-lifecycle-sidecar-*.jar app.jar

FROM amazoncorretto:25
RUN mkdir -p /var/log/asg-lifecycle
COPY --from=build /app/app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
