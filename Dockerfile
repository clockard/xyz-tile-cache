ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

# ── Stage 1: build go-pmtiles CLI ─────────────────────────────────────────────
# golang:1.26.4-alpine fixes CVE-2026-42504 (stdlib MIME header decode DoS,
# fixed in 1.26.4) in addition to earlier 1.26.x stdlib CVE fixes.
FROM golang:1.26.4-alpine AS builder
ARG PMTILES_VERSION=1.30.2
RUN apk add --no-cache git
RUN git clone --depth=1 --branch v${PMTILES_VERSION} https://github.com/protomaps/go-pmtiles /src
WORKDIR /src
# Upgrade otel/sdk to 1.43.0+ to fix CVE-2026-39883 (PATH hijacking via kenv)
RUN go get go.opentelemetry.io/otel/sdk@v1.43.0 \
 && go mod tidy \
 && CGO_ENABLED=0 go build -o /usr/local/bin/pmtiles .

# ── Stage 2: runtime image ────────────────────────────────────────────────────
FROM $JRE_IMAGE
ARG VERSION
WORKDIR /app
# gdal-tools provides gdal2tiles.py used by /layers/geotiff to tile uploaded GeoTIFFs.
# Alpine splits GDAL drivers into separate packages; png is required for gdal2tiles output,
# jpeg covers JPEG-compressed input TIFFs commonly used in remote sensing.
RUN apk add --no-cache gdal gdal-tools py3-gdal gdal-driver-png gdal-driver-jpeg \
 && apk upgrade --no-cache libxml2 libcrypto3 libssl3 openssl
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
COPY --from=builder /usr/local/bin/pmtiles /usr/local/bin/pmtiles
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8383
ENTRYPOINT ["/app/entrypoint.sh"]
