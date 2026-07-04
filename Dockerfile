ARG JRE_IMAGE=alpine:3.24.1

# ── Stage 1: build go-pmtiles CLI ─────────────────────────────────────────────
# golang:1.26-alpine tracks the latest Go 1.26.x patch, ensuring stdlib CVE
# fixes (CVE-2026-32280/32281/32283/33810 fixed in 1.26.2) are included.
FROM golang:1.26.4-alpine AS builder
ARG PMTILES_VERSION=1.30.3
RUN apk add --no-cache git
RUN git clone --depth=1 --branch v${PMTILES_VERSION} https://github.com/protomaps/go-pmtiles /src
WORKDIR /src
# Upgrade golang.org/x/net to 0.55.0+ to fix CVE-2026-33814 (HTTP/2 SETTINGS infinite loop),
# CVE-2026-25681/27136 (html XSS), CVE-2026-39821 (idna privilege escalation), CVE-2026-42502 (html Render)
# Upgrade golang.org/x/crypto to 0.52.0+ to fix CVE-2026-39827/39828/39829/39830/39832/39835/42508/46595/46597 (ssh)
# Upgrade otel/sdk to 1.43.0+ to fix CVE-2026-39883 (PATH hijacking via kenv)
RUN go get golang.org/x/net@v0.55.0 \
 && go get golang.org/x/crypto@v0.52.0 \
 && go get go.opentelemetry.io/otel/sdk@v1.43.0 \
 && go mod tidy \
 && CGO_ENABLED=0 go build -o /usr/local/bin/pmtiles .

# ── Stage 2: runtime image ────────────────────────────────────────────────────
FROM $JRE_IMAGE
ARG VERSION
WORKDIR /app
# Upgrade all packages to latest versions to address CVE fixes in base image
RUN apk upgrade --no-cache
# gdal-tools provides gdal2tiles.py used by /layers/geotiff to tile uploaded GeoTIFFs.
# Alpine splits GDAL drivers into separate packages; png is required for gdal2tiles output,
# jpeg covers JPEG-compressed input TIFFs commonly used in remote sensing.
RUN apk add --no-cache gdal gdal-tools py3-gdal gdal-driver-png gdal-driver-jpeg openjdk25-jre\
 && apk add --no-cache "libxml2>=2.13.9-r1" \
 && apk add --no-cache "openssl>=3.5.7-r0" "libcrypto3>=3.5.7-r0" "libssl3>=3.5.7-r0" "sqlite>=3.53.2" \
 && apk add --no-cache "p11-kit>=0.26.2-r0" "p11-kit-trust>=0.26.2-r0"
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
COPY --from=builder /usr/local/bin/pmtiles /usr/local/bin/pmtiles
COPY entrypoint.sh /app/entrypoint.sh
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"
RUN chmod +x /app/entrypoint.sh
EXPOSE 8383
ENTRYPOINT ["/app/entrypoint.sh"]
