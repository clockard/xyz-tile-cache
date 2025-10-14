ARG ALPINE_IMAGE=alpine
FROM $ALPINE_IMAGE:3.22.2
WORKDIR /app
RUN apk upgrade --available musl && \
    apk add --no-cache openjdk17-jre
COPY target/xyz-tile-cache-0.9.0.jar /app/xyz-tile-cache.jar
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]