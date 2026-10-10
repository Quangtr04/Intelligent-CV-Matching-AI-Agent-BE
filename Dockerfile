FROM eclipse-temurin:25-jre

WORKDIR /app

RUN useradd --system --create-home spring && mkdir -p /app/uploads && chown spring:spring /app/uploads
COPY target/*.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
