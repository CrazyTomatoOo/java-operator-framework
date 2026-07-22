FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S -g 1001 echo && adduser -S -u 1001 -G echo echo

COPY target/echo-operator-0.1.0-SNAPSHOT.jar /app/echo-operator.jar

RUN chown -R 1001:1001 /app

USER 1001:1001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/echo-operator.jar"]
