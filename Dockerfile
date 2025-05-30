ARG ALPINE_IMAGE=alpine
FROM $ALPINE_IMAGE:3.21.3
RUN apk upgrade --available musl
RUN apk add openjdk17-jre

RUN mkdir /app
COPY target/xyz-tile-cache-0.8.0.jar /app/xyz-tile-cache.jar
WORKDIR /app
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]