# XYZ Tile Cache

A caching proxy for slippy map tiles. It sits between your map client and any tile source, serving cached tiles from local disk on subsequent requests and switching to a fully offline mode when no network is available.

Supports XYZ (standard slippy map), WMTS-REST, and WMTS-KVP tile sources — including sources with a time dimension for weather radar and daily satellite imagery.

## How It Works

```mermaid
flowchart TD
    Client([Map Client]) -->|GET /tilesZYX/{layer}/{z}/{y}/{x}.png| App

    App --> MemCache{In-memory cache\n500 tiles}
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

All settings live in `application.yml`. Mount your own file at `/app/application.yml` inside the container to override the defaults.

### Core settings

```yaml
server:
  port: 8383            # listening port

xyz:
  baseTileDirectory: "/tmp/tiles"   # root directory for the tile disk cache
  maxTileStorage: 10000000000       # max disk usage in bytes (default 10 GB); stops writing when full
  offline: false                    # true = serve from disk only, never make outbound requests
  tileTimeoutSeconds: 5             # per-request timeout for outbound tile fetches
```

### Layers

Layers are the tile sources the proxy knows about. Each layer needs a name (used in the request URL) and a source URL template.

#### XYZ (standard slippy map)

```yaml
xyz:
  layers:
    - name: "osm"
      sourceType: XYZ                       # default, can be omitted
      urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      maxZoom: 19                           # requests above this zoom return 404
      attribution: "© OpenStreetMap contributors"
      headers:                             # optional custom request headers
        Referer: "https://myapp.example.com"
```

#### WMTS — RESTful style

```yaml
    - name: "usgs-topo"
      sourceType: WMTS_REST
      urlTemplate: "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/WMTS/tile/1.0.0/USGSTopo/default/GoogleMapsCompatible/{TileMatrix}/{TileRow}/{TileCol}"
      maxZoom: 17
```

The placeholders `{TileMatrix}`, `{TileRow}`, `{TileCol}` are substituted with `z`, `y`, `x` respectively.

#### WMTS — KVP (query parameter) style

```yaml
    - name: "imagery-wmts"
      sourceType: WMTS_KVP
      urlTemplate: "https://services.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/WMTS"
      wmtsLayerName: "World_Imagery"
      wmtsTileMatrixSet: "EPSG:3857"
      wmtsStyle: "default"
      wmtsFormat: "image/png"
```

The standard WMTS `GetTile` query string is built automatically from the `wmts*` properties.

#### Time-aware layers

Layers where the tile URL or WMTS request includes a date/time (weather radar, daily satellite composites):

```yaml
    # XYZ with time in the URL path
    - name: "accu-weather"
      sourceType: XYZ
      urlTemplate: "https://api.accuweather.com/maps/v1/radar/globalSIR/zxy/{time}/{z}/{y}/{x}.png?apikey=YOUR_KEY"
      timeFormat: "yyyy-MM-dd'T'HH:mm:ss'Z'"   # Java DateTimeFormatter pattern
      tileExpirationMinutes: 5                  # cached tiles are considered stale after N minutes

    # WMTS-KVP with a TIME dimension (e.g. NASA GIBS daily MODIS imagery)
    - name: "nasa-terra-truecolor"
      sourceType: WMTS_KVP
      urlTemplate: "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/wmts.cgi"
      wmtsLayerName: "MODIS_Terra_CorrectedReflectance_TrueColor"
      wmtsTileMatrixSet: "GoogleMapsCompatible_Level9"
      wmtsStyle: "default"
      wmtsFormat: "image/jpeg"
      wmtsTime: true                    # appends &TIME={time} to the KVP request
      timeFormat: "yyyy-MM-dd"          # NASA GIBS expects YYYY-MM-DD
      tileExpirationMinutes: 1440       # daily product — expire after 24 h
```

The current time is substituted into `{time}` using the layer's `timeFormat` at request time.

### Startup bounding box preloading

Define one or more bounding boxes to fill the disk cache automatically at startup. The proxy fetches every tile for all configured layers across all zoom levels up to `maxZoom`.

```yaml
xyz:
  boundingBoxes:
    - maxZoom: 8
      north: 50.0
      south: 24.0
      east: -66.0
      west: -125.0
```

> **Note:** Preloading is slow for large areas at high zoom levels. At zoom 12, the contiguous US contains roughly 250 000 tiles per layer.

## REST API

### Tile & utility endpoints (public)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/layers` | List all configured layers as JSON |
| `GET` | `/tilesZYX/{layer}/{z}/{y}/{x}.png` | Fetch a tile (ZYX coordinate order) |
| `GET` | `/tilesZXY/{layer}/{z}/{x}/{y}.png` | Fetch a tile (ZXY coordinate order) |
| `GET` | `/stats` | Cache statistics — tile counts and sizes by layer |
| `POST` | `/preload` | Trigger on-demand bounding box preloading |

All tile endpoints return `Content-Type: image/png` and `Access-Control-Allow-Origin: *`. Only one preload runs at a time; subsequent `POST /preload` calls while a preload is in progress are silently ignored.

### Layer management endpoints (requires `X-Admin-Key` header)

Write operations require the `X-Admin-Key: <value>` request header matching `xyz.adminKey` in your configuration. 
If `adminKey` is blank, write operations return `403 Forbidden`.
Default value is `xyz-admin`

| Method | Path | Success | Description |
|--------|------|---------|-------------|
| `POST` | `/layers` | 201 Created | Add a new layer |
| `PUT` | `/layers/{name}` | 200 OK | Replace an existing layer's configuration |
| `DELETE` | `/layers/{name}` | 204 No Content | Remove a layer and delete its cached tiles from disk |

Layer changes are persisted immediately to `{baseTileDirectory}/layers.json` and survive restarts. On first startup this file is created automatically from the layers defined in `application.yml`; subsequent restarts load from the file instead.

### GET /stats response

```json
{
  "totalTilesCached": 15000,
  "totalStorageBytes": 2500000000,
  "totalTilesServed": 45230,
  "layers": [
    { "name": "osm", "tilesCached": 5000, "storageBytesUsed": 1200000, "tilesServed": 20000 }
  ]
}
```

### POST /preload body

```json
{
  "layers": ["osm", "esri-satellite"],
  "boundingBox": {
    "north": 40.0,
    "south": 39.0,
    "east": -74.0,
    "west": -75.0,
    "maxZoom": 14
  }
}
```

## Docker Build

```bash
mvn clean package
docker build --build-arg VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout) -t xyz-tile-cache:latest .
```

## Docker Run

```bash
docker run \
  -v /path/to/tiles:/tmp/tiles \
  -v /path/to/application.yml:/app/application.yml \
  --rm --name xyz \
  -p 127.0.0.1:8383:8383/tcp \
  xyz-tile-cache:latest
```

`/path/to/tiles` is the local directory where tiles will be stored. `/path/to/application.yml` is your configuration file; omit the `-v` flag to use the defaults (all included public sources, `/tmp/tiles` storage).

After startup, tiles are served at:

```
http://localhost:8383/tilesZYX/{layer}/{z}/{y}/{x}.png
```

For example:
```
http://localhost:8383/tilesZYX/osm/10/512/256.png
```

## Offline Mode

Set `xyz.offline: true` to prevent any outbound requests. The proxy will serve tiles from the local disk cache only and return 404 for anything not already cached. This is useful for field deployments or air-gapped environments where network access is unavailable.

Pre-populate the cache by running with `offline: false` and using the bounding box preload feature, then switch to `offline: true` for the deployment.
