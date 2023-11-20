FROM adoptopenjdk/openjdk11
RUN mkdir /app
COPY target/xyz-tile-cache-0.4.0.jar /app/xyz-tile-cache.jar
WORKDIR /app
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]