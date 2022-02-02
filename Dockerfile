FROM adoptopenjdk/openjdk11:jdk-11.0.11_9-alpine-slim
RUN mkdir /app
COPY target/xyz-tile-cache-0.0.1-SNAPSHOT.jar /app/xyz-tile-cache.jar
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]