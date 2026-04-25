ARG ALPINE_IMAGE=alpine
FROM $ALPINE_IMAGE:3.23.4
ARG VERSION
WORKDIR /app
RUN apk upgrade --available musl && \
    apk add --no-cache openjdk17-jre
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
EXPOSE 8383
CMD ["java", "-jar", "/app/xyz-tile-cache.jar"]