ARG JRE_IMAGE=alpine:3.24.2

# ── Stage 1: build go-pmtiles CLI ─────────────────────────────────────────────
# golang:1.26-alpine tracks the latest Go 1.26.x patch, ensuring stdlib CVE
# fixes (CVE-2026-32280/32281/32283/33810 fixed in 1.26.2; CVE-2026-39822
# os.Root symlink-following traversal fixed in 1.26.5; CVE-2026-39821 idna
# Punycode privilege escalation and CVE-2026-46600 dnsmessage DoS fixed in
# 1.26.6) are included.
FROM golang:1.26.8-alpine AS builder
ARG PMTILES_VERSION=1.31.2
RUN apk add --no-cache git
RUN git clone --depth=1 --branch v${PMTILES_VERSION} https://github.com/protomaps/go-pmtiles /src
WORKDIR /src
# Upgrade golang.org/x/net to 0.59.0+ to fix CVE-2026-25680/25681/27136/39821/42502/42506 (HTML parsing/Render CPU & memory issues, idna Punycode privilege escalation)
# Upgrade otel/sdk to 1.46.0+ to fix CVE-2026-39883 (PATH hijacking via kenv)
# Upgrade golang.org/x/text to 0.42.0+ to fix CVE-2026-56852 (norm.Iter infinite loop)
# Upgrade grpc-go to 1.83.2+ to fix GHSA-hrxh-6v49-42gf (xDS RBAC and HTTP/2 vulnerabilities), CVE-2026-84304,
# and CVE-2026-84445 (xDS servers DoS via missing :authority/Host headers). Held at 1.83.2 deliberately:
# GO-2026-6443 shows CVE-2026-84445 regressed in v1.84.0 (affected range resumes at v1.84.0-dev, before
# v1.85.0-dev) — do not bump grpc-go until a fixed 1.84.x or 1.85.0 release ships.
# Upgrade golang.org/x/crypto to 0.57.0+ to fix CVE-2026-39827/39828/39829/39830/39831/39832/39835/42508/46595/46597/56854
# (ssh client/server/agent/knownhosts issues, auth bypass via unenforced source-address restrictions), plus
# CVE-2026-56855/78662 (crafted post- and pre-establishment channel messages deadlocking the ssh connection)
# fixed in 0.56.0.
# Upgrade aws-sdk-go-v2 eventstream/s3 to fix GHSA-xmrv-pmrh-hhx2 (DoS panic decoding
# malformed event-stream messages).
# Upgrade mongo-driver to 1.17.7+ to fix CVE-2026-2303.
# x/crypto is only an indirect dependency here, so it must be the LAST go get: `go mod tidy`/subsequent
# `go get` calls recompute the module graph and drop indirect version pins that aren't backed by a
# direct requirement, silently reverting to whatever lower version other deps demand.
RUN go get golang.org/x/net@v0.59.0 \
 && go get go.opentelemetry.io/otel/sdk@v1.46.0 \
 && go get golang.org/x/text@v0.42.0 \
 && go get google.golang.org/grpc@v1.83.2 \
 && go get github.com/aws/aws-sdk-go-v2/aws/protocol/eventstream@v1.7.8 \
 && go get github.com/aws/aws-sdk-go-v2/service/s3@v1.97.3 \
 && go get go.mongodb.org/mongo-driver@v1.17.7 \
 && go get golang.org/x/crypto@v0.57.0 \
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
# Run as non-root (DS-0002). Fixed UID/GID so bind-mounted host dirs (e.g. /tmp/tiles)
# can be chowned to match from outside the container.
RUN addgroup -g 1000 xyz && adduser -D -u 1000 -G xyz xyz \
 && chown -R xyz:xyz /app
USER xyz
EXPOSE 8383
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8383/actuator/health || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
