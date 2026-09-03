ARG JRE_IMAGE=alpine:3.24.1

# ── Stage 1: build go-pmtiles CLI ─────────────────────────────────────────────
# golang:1.26-alpine tracks the latest Go 1.26.x patch, ensuring stdlib CVE
# fixes (CVE-2026-32280/32281/32283/33810 fixed in 1.26.2; CVE-2026-39822
# os.Root symlink-following traversal fixed in 1.26.5; CVE-2026-39821 idna
# Punycode privilege escalation and CVE-2026-46600 dnsmessage DoS fixed in
# 1.26.6) are included.
FROM golang:1.26.6-alpine AS builder
ARG PMTILES_VERSION=1.30.3
RUN apk add --no-cache git
RUN git clone --depth=1 --branch v${PMTILES_VERSION} https://github.com/protomaps/go-pmtiles /src
WORKDIR /src
# Upgrade golang.org/x/net to 0.55.0+ to fix CVE-2026-25680/25681/27136/39821/42502/42506 (HTML parsing/Render CPU & memory issues, idna Punycode privilege escalation)
# Upgrade otel/sdk to 1.43.0+ to fix CVE-2026-39883 (PATH hijacking via kenv)
# Upgrade golang.org/x/text to 0.39.0+ to fix CVE-2026-56852 (norm.Iter infinite loop)
# Upgrade grpc-go to 1.83.1+ to fix GHSA-hrxh-6v49-42gf (xDS RBAC and HTTP/2 vulnerabilities) and CVE-2026-84304
# Upgrade golang.org/x/crypto to 0.55.0+ to fix CVE-2026-39827/39828/39829/39830/39831/39832/39835/42508/46595/46597/56854
# (ssh client/server/agent/knownhosts issues, auth bypass via unenforced source-address restrictions).
# x/crypto is only an indirect dependency here, so it must be the LAST go get: `go mod tidy`/subsequent
# `go get` calls recompute the module graph and drop indirect version pins that aren't backed by a
# direct requirement, silently reverting to whatever lower version other deps demand.
RUN go get golang.org/x/net@v0.55.0 \
 && go get go.opentelemetry.io/otel/sdk@v1.43.0 \
 && go get golang.org/x/text@v0.39.0 \
 && go get google.golang.org/grpc@v1.83.1 \
 && go get golang.org/x/crypto@v0.55.0 \
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
# py3-gdal pulls in python3; pin it (and its pyc/bytecode-cache split packages) to
# 3.14.7-r0+ to fix CVE-2026-7210 (expat DoS via crafted XML document).
RUN apk add --no-cache gdal gdal-tools py3-gdal gdal-driver-png gdal-driver-jpeg\
 && apk add --no-cache "openjdk25-jre-headless>=25.0.4_p7-r0" \
 && apk add --no-cache "libxml2>=2.13.9-r1" \
 && apk add --no-cache "openssl>=3.5.8-r0" "libcrypto3>=3.5.8-r0" "libssl3>=3.5.8-r0" "sqlite>=3.53.2" \
 && apk add --no-cache "c-ares>=1.34.8-r0" "libcurl>=8.21.0-r0" "libexpat>=2.8.4-r0" "giflib>=5.2.2-r2" \
    "p11-kit>=0.26.2-r0" "p11-kit-trust>=0.26.2-r0" \
 && apk add --no-cache "pyc>=3.14.7-r0" "python3>=3.14.7-r0" "python3-pyc>=3.14.7-r0" \
    "python3-pycache-pyc0>=3.14.7-r0"
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
COPY --from=builder /usr/local/bin/pmtiles /usr/local/bin/pmtiles
COPY entrypoint.sh /app/entrypoint.sh
ENV JAVA_HOME=/usr/lib/jvm/java-25-openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"
RUN chmod +x /app/entrypoint.sh
EXPOSE 8383
ENTRYPOINT ["/app/entrypoint.sh"]
