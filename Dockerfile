ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine
FROM $JRE_IMAGE
ARG VERSION
WORKDIR /app
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]
