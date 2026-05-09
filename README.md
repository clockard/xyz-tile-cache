# XYZ Tile Cache

A caching proxy for map tiles. It sits between your map client and any tile source, serving cached tiles from local disk on subsequent requests and switching to a fully offline mode when no network is available.

Capabilities:

- Raster tile sources — XYZ (standard slippy map), WMTS-REST, and WMTS-KVP.
- Time-aware sources — weather radar, daily satellite imagery (URL `{time}` substitution or WMTS `TIME` dimension).
- User-uploaded local layers — upload a GeoTIFF and the proxy tiles it with `gdal2tiles` and serves it as a `LOCAL` layer.
- Vector tiles — bundled `world_z0-7.pmtiles` ships in the image; larger areas can be downloaded on demand from a Protomaps-style PMTiles source.
- Authenticated layer management — JSON-driven CRUD with Keycloak (JWT) or a single shared admin token, plus per-layer ACLs.
- A web UI on `/` for browsing layers, triggering preloads, and managing layers (toggleable).

## How It Works

```mermaid
flowchart TD
    Client([Map Client]) -->|GET /tilesZYX/{layer}/{z}/{y}/{x}.png| App

    App --> ACL{Per-layer ACL}
    ACL -->|deny| Forbidden([401 / 403])
    ACL -->|allow| MemCache{In-memory cache\n500 tiles}

    MemCache -->|hit| Response([Return tile])
    MemCache -->|miss| DiskCache{Disk cache}

    DiskCache -->|hit| Response
    DiskCache -->|miss / offline=false| CB{Circuit breaker}
    DiskCache -->|miss / offline=true| NotFound([404 Not Found])

    CB -->|BLOCK — source recently failed| NotFound
    CB -->|PROCEED or RETRY| Fetch[HTTP fetch\nfrom tile source]

    Fetch -->|success| AsyncWrite[Async write\nto disk]
    AsyncWrite --> Response
    Fetch -->|failure| SourceFailed[Mark source failed\nstart backoff]
    SourceFailed --> NotFound
```

On a cache miss the loader first checks disk, then the circuit breaker state, then makes an outbound HTTP request. Successful tiles are written to disk asynchronously so the response is not delayed. A failed source is blocked with exponential backoff (100 ms → 60 s) to avoid hammering unavailable upstream servers.

## Configuration

All settings live in `application.yml`. Mount your own file at `/app/application.yml` inside the container to override the defaults shipped in the image.

Spring Boot maps any property to the matching environment variable using its standard rules (`xyz.auth.mode` → `XYZ_AUTH_MODE`, `xyz.baseTileDirectory` → `XYZ_BASETILEDIRECTORY`, etc.), so most tuning can be done with `-e` flags without remounting a config file.

### Core settings

```yaml
server:
  port: 8383                       # listening port

spring:
  servlet:
    multipart:
      max-file-size: 2GB           # cap on GeoTIFF uploads
      max-request-size: 2GB

xyz:
  baseTileDirectory: "/tmp/tiles"  # root directory for the tile disk cache
  minFreeDiskBytes: 1073741824     # stop caching new tiles when free disk drops below this (default 1 GB)
  offline: false                   # true = serve from disk only, never make outbound requests
  tileTimeoutSeconds: 5            # per-request timeout for outbound tile fetches
  layerSyncSeconds: 10             # how often to re-read layers.json so multiple instances stay in sync
  uiEnabled: true                  # set to false to disable the web UI (UI paths return 404)
  adminRole: admin                 # Keycloak realm role required for write operations
```

`minFreeDiskBytes` replaces the older `maxTileStorage` byte cap. The proxy keeps writing tiles until the underlying filesystem has less than this many bytes free, then stops accepting new tiles. Existing tiles are still served.

`layerSyncSeconds` is used when more than one instance of the proxy shares the same `baseTileDirectory` (e.g. behind a load balancer with a shared volume). All layer mutations are persisted to `{baseTileDirectory}/layers.json`; each instance polls the file at this interval and reconciles its in-memory layer map.

### Authentication

```yaml
xyz:
  auth:
    mode: jwt              # jwt | token
    adminToken: ""         # used when mode=token
    clientId: xyz-tile-cache  # OAuth2 client id reported to the UI in jwt mode

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:}
```

Two auth backends are supported:

- **`jwt`** (default) — Keycloak resource-server flow. All write endpoints require a Bearer JWT; the user must hold the realm role configured in `xyz.adminRole` (default `admin`). The realm `groups` claim is used for per-layer group ACLs. Set `KEYCLOAK_ISSUER_URI` to your realm URL.
- **`token`** — a single static admin token. Anyone presenting `Authorization: Bearer <xyz.auth.adminToken>` is treated as admin. Per-layer ACLs reduce to "public reads + admin reads" because there is no per-user identity. Useful for headless deployments or when Keycloak is overkill. Leave `KEYCLOAK_ISSUER_URI` unset in this mode.

Read access is anonymous-friendly: layers with empty `allowedUsers` and `allowedGroups` lists are public, others require an authenticated principal that matches one of the entries (or the admin role).

### Layers

Layers are the tile sources the proxy knows about. Each layer needs an `id` (used in tile URLs and as the storage subdirectory), a human-readable `name` (shown in the UI), and a source URL template. If `id` is omitted, `name` is used as the id (back-compat with older configs).

#### Per-layer common fields

| Field | Default | Description |
|-------|---------|-------------|
| `id` | — | Stable identifier used in tile URLs and on disk. |
| `name` | — | Display name shown in the UI. |
| `sourceType` | `XYZ` | One of `XYZ`, `WMTS_REST`, `WMTS_KVP`, `LOCAL`. |
| `urlTemplate` | — | Source URL pattern (omit for `LOCAL`). |
| `maxZoom` | `22` | Tile requests above this zoom return 404. |
| `attribution` | — | Free-form string included in `GET /layers` responses. |
| `headers` | `{}` | Map of HTTP headers added to outbound tile fetches (e.g. `Referer`, `User-Agent`). |
| `tileExpirationMinutes` | `0` | Cached tiles older than this are refetched. `0` = never expire. |
| `timeFormat` | `yyyy-MM-dd'T'HH:mm:ss'Z'` | Java `DateTimeFormatter` pattern for `{time}` substitution. |
| `allowedUsers` | `[]` | Read-access ACL — usernames from the JWT `preferred_username` claim. |
| `allowedGroups` | `[]` | Read-access ACL — group names from the JWT `groups` claim. |

Empty `allowedUsers` and `allowedGroups` together mean "public layer". A layer with either list non-empty requires authentication for reads; admins always pass.

#### XYZ (standard slippy map)

```yaml
xyz:
  layers:
    - id: "osm"
      name: "OpenStreetMap"
      sourceType: XYZ                              # default, can be omitted
      urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      maxZoom: 19
      attribution: "© OpenStreetMap contributors"
      headers:
        Referer: "https://myapp.example.com"
```

#### WMTS — RESTful style

```yaml
    - id: "usgs-topo"
      name: "USGS Topo"
      sourceType: WMTS_REST
      urlTemplate: "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/WMTS/tile/1.0.0/USGSTopo/default/GoogleMapsCompatible/{TileMatrix}/{TileRow}/{TileCol}"
      maxZoom: 17
```

The placeholders `{TileMatrix}`, `{TileRow}`, `{TileCol}` are substituted with `z`, `y`, `x` respectively.

#### WMTS — KVP (query parameter) style

```yaml
    - id: "imagery-wmts"
      name: "World Imagery (WMTS)"
      sourceType: WMTS_KVP
      urlTemplate: "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/WMTS"
      wmtsLayerName: "World_Imagery"
      wmtsTileMatrixSet: "EPSG:3857"     # default
      wmtsStyle: "default"               # default
      wmtsFormat: "image/png"            # default
```

The standard WMTS `GetTile` query string is built automatically from the `wmts*` properties.

#### Time-aware layers

Layers where the tile URL or WMTS request includes a date/time (weather radar, daily satellite composites):

```yaml
    # XYZ with time in the URL path
    - id: "accu-weather"
      name: "AccuWeather Radar"
      sourceType: XYZ
      urlTemplate: "https://api.accuweather.com/maps/v1/radar/globalSIR/zxy/{time}/{z}/{y}/{x}.png?apikey=YOUR_KEY"
      timeFormat: "yyyy-MM-dd'T'HH:mm:ss'Z'"
      tileExpirationMinutes: 5

    # WMTS-KVP with a TIME dimension (NASA GIBS daily MODIS imagery)
    - id: "nasa-terra-truecolor"
      name: "NASA Terra True Color"
      sourceType: WMTS_KVP
      urlTemplate: "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/wmts.cgi"
      wmtsLayerName: "MODIS_Terra_CorrectedReflectance_TrueColor"
      wmtsTileMatrixSet: "GoogleMapsCompatible_Level9"
      wmtsFormat: "image/jpeg"
      wmtsTime: true                          # appends &TIME={time} to the KVP request
      timeFormat: "yyyy-MM-dd"                # NASA GIBS expects YYYY-MM-DD
      tileExpirationMinutes: 1440             # daily product — expire after 24 h
```

The current time is substituted into `{time}` using the layer's `timeFormat` at request time.

#### LOCAL layers (uploaded GeoTIFFs)

`LOCAL` layers have no upstream URL — tiles are read from disk only. They are typically created by uploading a GeoTIFF to `POST /layers/geotiff` (see the API section); the proxy runs `gdal2tiles.py` against the file and writes XYZ tiles into `{baseTileDirectory}/{id}/`.

### Vector tiles (PMTiles)

```yaml
xyz:
  vector:
    bundledPath: "${xyz.baseTileDirectory}/vector/world_z0-7.pmtiles"
    downloadDirectory: "${xyz.baseTileDirectory}/vector"
    sourceUrl: "${PMTILES_SOURCE_URL:https://build.protomaps.com/planet.pmtiles}"
    maxDownloadZoom: 15
    enabled: true
```

| Field | Description |
|-------|-------------|
| `enabled` | When `false`, vector endpoints return empty results. |
| `bundledPath` | Path to a PMTiles file shipped/preloaded with the deployment. The Docker image bakes a `world_z0-7.pmtiles` planet basemap at build time and copies it here. |
| `downloadDirectory` | Where on-demand PMTiles bundles are written by preloads. Required when issuing preloads with `includeVector: true`. |
| `sourceUrl` | HTTP(S) URL to a PMTiles archive (Protomaps-style range-request friendly). The proxy uses `pmtiles extract` to download only the bytes covering the requested bounding box. |
| `maxDownloadZoom` | Cap on the zoom level fetched during a vector preload. |

Override `sourceUrl` per environment with the `PMTILES_SOURCE_URL` env var.

### Startup bounding-box preloading

Define one or more bounding boxes to fill the disk cache automatically at startup. The proxy fetches every tile for all configured layers across zoom 0 → `maxZoom`.

```yaml
xyz:
  boundingBoxes:
    - preCache: true        # set true to actually run at startup; false to skip
      maxZoom: 8
      north: 50.0
      south: 24.0
      east: -66.0
      west: -125.0
```

Multiple bounding boxes are processed in parallel. Preloading is slow for large areas at high zoom levels — at zoom 12, the contiguous US contains roughly 250 000 tiles per layer.

For ad-hoc preloads use the `POST /preloads` API instead; those are persisted, can be named, and can target a specific subset of layers.

## REST API

### Tile endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/tilesZYX/{layer}/{z}/{y}/{x}.png` | per-layer | Fetch a tile (ZYX coordinate order). |
| `GET` | `/tilesZXY/{layer}/{z}/{x}/{y}.png` | per-layer | Fetch a tile (ZXY coordinate order). |
| `GET` | `/vector/{z}/{x}/{y}` | none | Fetch a Mapbox Vector Tile (`application/x-protobuf`, gzip when applicable). |

All tile responses set `Access-Control-Allow-Origin: *`. Tile endpoints return `401` for anonymous requests against private layers and `403` when authenticated principals lack access.

### Layer management

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/layers` | none (filtered) | List configured layers. The response contains only layers the caller can read. |
| `POST` | `/layers` | admin | Add a new layer (JSON body matching the `Layer` schema). 409 on duplicate id. |
| `PUT` | `/layers/{id}` | admin | Replace an existing layer. 404 if missing. |
| `DELETE` | `/layers/{id}` | admin | Remove a layer and delete its cached tiles from disk. |
| `POST` | `/layers/geotiff` | admin | Multipart upload (`name`, `file`, optional `allowedUsers`, `allowedGroups`). Tiles the GeoTIFF with `gdal2tiles.py` and registers it as a `LOCAL` layer. |

Layer changes are persisted immediately to `{baseTileDirectory}/layers.json` and survive restarts. On first startup the file is created from the layers defined in `application.yml`; subsequent restarts (and other instances pointed at the same volume) load from this file instead.

### Preloads

The current preload model is a first-class entity with its own ACL. Legacy `POST /preload` is still accepted for back-compat but does not persist a record.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/preloads` | none (filtered) | List persisted preloads visible to the caller. |
| `POST` | `/preloads` | admin | Create a preload (see body below). 409 if a vector download is already in progress; 503 if vector requested without `xyz.vector.downloadDirectory`. |
| `DELETE` | `/preloads/{id}` | admin | Remove a preload record (does not delete cached tiles). |
| `POST` | `/preload` | admin | Legacy fire-and-forget preload (not persisted). |
| `POST` | `/vector/preload` | admin | Convenience endpoint that submits a vector-only preload. |

`POST /preloads` body:

```json
{
  "name": "philly-z14",
  "boundingBox": {
    "north": 40.1, "south": 39.8, "east": -74.9, "west": -75.3
  },
  "maxZoom": 14,
  "layers": ["osm", "esri-satellite"],
  "includeVector": false,
  "allowedUsers": [],
  "allowedGroups": ["team-imagery"]
}
```

If `includeVector` is true, the proxy runs `pmtiles extract` against `xyz.vector.sourceUrl` to materialize a PMTiles bundle covering the bounding box (capped at `xyz.vector.maxDownloadZoom`) and writes it under `xyz.vector.downloadDirectory`. Only one vector download runs at a time.

### Stats

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/stats` | none | Per-instance tile-serve counters and free disk space. |

```json
{
  "instanceId": "12345@hostname",
  "tilesServedByInstance": 45230,
  "diskFreeBytes": 132100997120,
  "layers": [
    { "name": "osm", "tilesServedByInstance": 20000 },
    { "name": "esri-satellite", "tilesServedByInstance": 25230 }
  ]
}
```

The counters are in-memory and reset on restart. With multiple instances behind a load balancer, query each one individually and aggregate client-side.

### Auth discovery

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/auth/config` | none | Returns `{mode, issuerUri, clientId}` (jwt) or `{mode: "token"}` so the UI knows which login flow to use. |

## Authentication & Authorization

- All `POST`/`PUT`/`DELETE` endpoints require the realm role configured in `xyz.adminRole` (default `admin`).
- All `GET` endpoints are anonymous-friendly. Per-layer reads are gated by `LayerAccessService`:
  - empty `allowedUsers` *and* `allowedGroups` → public.
  - admin role → bypasses ACLs.
  - else the JWT must contain `preferred_username` in `allowedUsers` or any `groups` entry in `allowedGroups`.
- In `token` mode there is no per-user identity, so private layers are effectively admin-only.

## Local Keycloak (for testing the JWT flow)

```bash
docker compose up -d keycloak                   # starts Keycloak on :8080, imports the realm
bash scripts/keycloak/setup.sh                  # waits for ready, prints token-fetch curls
bash scripts/keycloak/setup.sh --reseed         # delete + re-import realm
```

Test users (all password `password`):

| User | Roles | Groups |
|------|-------|--------|
| `alice` | `admin` | `admins` |
| `bob` | — | `team-foresters` |
| `carol` | — | `team-imagery` |
| `dan` | — | — |

Token fetch:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/xyz-tile-cache/protocol/openid-connect/token \
  -d grant_type=password -d client_id=xyz-tile-cache \
  -d username=alice -d password=password | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8383/layers
```

Override the issuer URI per environment with `KEYCLOAK_ISSUER_URI`.

## Build & Run

### Local build

```bash
mvn clean package
mvn spring-boot:run
```

`fmt-maven-plugin` runs in the `validate` phase and will fail the build if code is not formatted. Run `mvn fmt:format` to auto-format.

### Docker build

```bash
mvn clean package
docker build \
  --build-arg VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout) \
  -t xyz-tile-cache:latest .
```

The image bundles `gdal-tools` (for GeoTIFF tiling), the `pmtiles` CLI, and a world basemap PMTiles file extracted at build time from `https://build.protomaps.com/<date>.pmtiles`.

### Docker run

```bash
docker run \
  -v /path/to/tiles:/tmp/tiles \
  -v /path/to/application.yml:/app/application.yml \
  --rm --name xyz \
  -p 127.0.0.1:8383:8383/tcp \
  xyz-tile-cache:latest
```

Omit the second `-v` to use the bundled defaults (all included public sources, `/tmp/tiles` storage, JWT auth pointing at `KEYCLOAK_ISSUER_URI`).

For a quick token-mode deployment with no Keycloak:

```bash
docker run \
  -v /path/to/tiles:/tmp/tiles \
  -e XYZ_AUTH_MODE=token \
  -e XYZ_AUTH_ADMINTOKEN=changeme \
  --rm --name xyz \
  -p 127.0.0.1:8383:8383/tcp \
  xyz-tile-cache:latest
```

### Docker Compose

The bundled `docker-compose.yml` brings up Keycloak alongside the tile-cache and wires `KEYCLOAK_ISSUER_URI` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` so the two containers can talk:

```bash
docker compose up -d
bash scripts/keycloak/setup.sh
```

## Offline Mode

Set `xyz.offline: true` to prevent any outbound requests. The proxy will serve tiles from the local disk cache only and return 404 for anything not already cached. This is useful for field deployments or air-gapped environments where network access is unavailable.

Pre-populate the cache by running with `offline: false` and using either the startup `boundingBoxes` config or the `POST /preloads` API (with `includeVector: true` to also stage a vector PMTiles bundle), then switch to `offline: true` for the deployment.

## CI/CD

Two GitHub Actions workflows live in `.github/workflows/`:

- **`ci.yml`** — Runs `clean verify` on every push to `main` and on all PRs targeting `main`.
- **`release.yml`** — Manual `workflow_dispatch` trigger. Sets the requested version in `pom.xml`, builds, tests, pushes a Docker image to Docker Hub (tagged with the version and `latest`), commits and tags the release, then bumps `pom.xml` to the next patch SNAPSHOT and pushes everything back to `main`.

Required secrets for the release workflow: `DOCKER_USERNAME`, `DOCKER_TOKEN`.
