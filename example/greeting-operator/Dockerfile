FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/greeting-operator-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]