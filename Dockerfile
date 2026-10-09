FROM eclipse-temurin:25-jre

WORKDIR /app

RUN useradd --system --create-home spring
COPY target/*.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
