# XYZ Tile Cache
A slippy (xyz) tile proxy server that caches tiles for fast local retrieval or offline loading.

Bounding boxes with max zoom levels can be defined for preloading tile data. If a tile is not 
locally available the server will reach out to the source to try and retrieve the tile. If successful
the new tile will be cached as long as the maxTileStorage has not been surpassed. 

## Configuration Parameters
```yaml
server:
  port: 8383

xyz:
  baseTileDirectory: "/tmp/tiles"
  maxTileStorage: 1000000000
  layers:
    - name: "satelite"
      urlTemplate: "http://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
  boundingBoxes:
    - preCache: true
      maxZoom: 5
      north: 70
      south: -70
      east: 179
      west: -179
```



## Docker Build
`docker build . -t xyz-tile-cache:latest`

## Docker Run
This example assumes a local tile repository at `/tmp/tiles` and a configuration file at `/tmp/application.yml`

`docker run -v /tmp/tiles:/tmp/tiles -v /tmp/application.yml:/app/application.yml --rm --name xyz -p 127.0.0.1:8383:8383/tcp xyz-tile-cache:latest`

By default tile layers will be available at `http://localhost:8383/tilesZYX/satelite/{z}/{y}/{x}.png`