ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

# ── Stage 1: download go-pmtiles CLI and world z0-7 basemap ──────────────────
FROM alpine:3 AS builder
ARG PMTILES_VERSION=1.30.2
ARG PMTILES_DATE=20260425
ARG TARGETARCH
RUN apk add --no-cache wget \
 && PMTILES_ARCH=$([ "$TARGETARCH" = "amd64" ] && echo "x86_64" || echo "arm64") \
 && wget -qO- "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_Linux_${PMTILES_ARCH}.tar.gz" \
    | tar -xz -C /usr/local/bin pmtiles
# Uses HTTP range requests — downloads only the tiles needed, not the full planet file
RUN pmtiles extract "https://build.protomaps.com/${PMTILES_DATE}.pmtiles" /tmp/world_z0-7.pmtiles \
    --maxzoom=7

# ── Stage 2: runtime image ────────────────────────────────────────────────────
FROM $JRE_IMAGE
ARG VERSION
WORKDIR /app
COPY target/xyz-tile-cache-${VERSION}.jar /app/xyz-tile-cache.jar
COPY --from=builder /usr/local/bin/pmtiles /usr/local/bin/pmtiles
COPY --from=builder /tmp/world_z0-7.pmtiles /app/data/world_z0-7.pmtiles
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8383
ENTRYPOINT ["/app/entrypoint.sh"]
