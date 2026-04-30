#!/bin/sh
# Seed the bundled world PMTiles into the tiles directory on first run so it
# is persisted across container restarts alongside user downloads.
# XYZ_BASETILEDIRECTORY mirrors Spring Boot's xyz.baseTileDirectory binding.
TILES_DIR="${XYZ_BASETILEDIRECTORY:-/tmp/tiles}"
VECTOR_DIR="$TILES_DIR/vector"
mkdir -p "$VECTOR_DIR"
SEED="/app/data/world_z0-7.pmtiles"
TARGET="$VECTOR_DIR/world_z0-7.pmtiles"
if [ -f "$SEED" ] && [ ! -f "$TARGET" ]; then
  echo "Seeding bundled PMTiles to $TARGET"
  cp "$SEED" "$TARGET"
fi
exec java -jar /app/xyz-tile-cache.jar "$@"
