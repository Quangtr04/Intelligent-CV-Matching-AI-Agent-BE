FROM eclipse-temurin:25-jre AS java-runtime

FROM debian:trixie-slim

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV APP_PDF_BROWSER_PATH=/usr/bin/chromium
ENV APP_PDF_NO_SANDBOX=true

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates chromium fonts-dejavu-core fonts-liberation tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN useradd --system --create-home spring && mkdir -p /app/uploads && chown spring:spring /app/uploads
COPY target/*.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
