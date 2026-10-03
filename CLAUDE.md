# CLAUDE.md

Be concise. Skip pleasantries. Use bullet points.

## Commands

```bash
mvn clean package                           # build (also builds the Docker image; add -Ddocker.skip=true to skip)
mvn spring-boot:run                         # run locally
mvn test                                    # all tests
mvn test -Dtest=ClassName#methodName        # single test
mvn fmt:format                              # auto-format (fmt-maven-plugin runs in `validate`; build fails on bad formatting)

docker build . -t xyz-tile-cache:latest
docker run -v /tmp/tiles:/tmp/tiles -v /tmp/application.yml:/app/application.yml \
  --rm --name xyz -p 127.0.0.1:8383:8383/tcp xyz-tile-cache:latest
```

## CI/CD

`.github/workflows/`:
- `ci.yml` — `clean verify` on push to `main` and PRs to `main`.
- `release.yml` — manual `workflow_dispatch` with a release version input. Sets `pom.xml`, builds, pushes Docker image (version + `latest`), tags the release, bumps to next `-SNAPSHOT`, pushes back to `main`.
- `check-sources.yml`, `scan.yml` — supplemental checks.

Release secrets: `DOCKER_USERNAME`, `DOCKER_TOKEN`. If branch protection blocks the release push, swap `GITHUB_TOKEN` for a `GH_PAT` in `release.yml` checkout.

## Architecture

Spring Boot 4.1 tile proxy (Spring Framework 7, Security 7, Jackson 3, Tomcat 11, Java 25) on port 8383. Caches XYZ raster tiles, MVT vector tiles, and ingested GeoTIFFs. Supports XYZ, WMTS-REST, WMTS-KVP, and PMTiles sources.

### Tile request flow

```
GET /tilesZYX/{layer}/{z}/{y}/{x}.{ext}
  → TileController → TileSourceHandlerRegistry → TileSourceHandler (by Layer.SourceType)
       RasterTileHandler: Caffeine LoadingCache (byte-bounded, xyz.tileCacheBytes; keyed by layerId+z/x/y)
         miss → CacheLoader (Online or Offline, by xyz.offline)
              online: HTTP fetch from layer source
              offline: read {baseTileDir}/{layer}/{z}/{x}/{y}.<ext>
         → TileWriter.storeTile() (@Async, off the response path)
       VectorPmtilesHandler: VectorPmtilesManager → PmtilesReader/RemotePmtilesReader
  → return tile bytes
```

### Key classes

- `XyzTileCacheApplication` — entry point + startup initialization.
- `TileController` — raster tile endpoints (`/tilesZYX`, `/tilesZXY`), legacy `/preload`; dispatches via `TileSourceHandlerRegistry`.
- `TileSourceHandler` (interface) / `RasterTileHandler` / `VectorPmtilesHandler` / `TileSourceHandlerRegistry` — handler dispatch pattern by `Layer.SourceType`.
- `LayerController` — `/layers` CRUD; persisted via `LayerStore` to `layers.json`.
- `PreloadController` / `PreloadService` / `PreloadStore` — bounding-box preload jobs.
- `VectorPmtilesManager` — manages PMTiles vector layers; backed by `PmtilesReader` (local) / `RemotePmtilesReader`.
- `PmtilesDownloader` — downloads remote PMTiles files.
- `ImportExportController` / `ImportExportService` / `ExportService` — tile package import/export (`/import`, `/export`, `/exports`).
- `GeoTiffController` / `GeoTiffTiler` — GeoTIFF upload + tiling.
- `ConfigController` — `/config/offline` (offline mode discovery).
- `StatsController`, `AuthConfigController` — `/stats`, `/auth/config` (UI auth discovery).
- `XyzConfiguration` — `@ConfigurationProperties("xyz")` for `application.yml`.
- `Layer` — source URL template + circuit-breaker blocking (exponential 100ms→60s; states PROCEED / BLOCK / RETRY).
- `OnlineCacheLoader` / `OfflineCacheLoader` — Caffeine `CacheLoader` impls for HTTP vs. disk (stale-if-error fallback).
- `TileWriter` — async persistence; enforces `xyz.minFreeDiskBytes`; inventories on startup.
- `XyzUtil` — XYZ ↔ lat/lon math; bbox → tile enumeration.
- `SecurityConfig`, `AdminTokenAuthFilter`, `LayerAccessService`, `UiFilter` — see Authentication.

### Configuration

`xyz.layers` in `application.yml` defines layers. Each has `id`, `name`, `source` URL template (`{x}`, `{y}`, `{z}`, optional `{time}`), and optional `wmtsParams`. `xyz.boundingBoxes` triggers background preload at startup.

### Testing

- WireMock mocks tile HTTP sources; `@TempDir` + `@DynamicPropertySource` isolate disk/config.
- Integration tests use `MockMvc` with full Spring context. `LayerTest` is a pure unit test for blocking strategy.
- `test` Spring profile (set in `surefire`) loads `src/test/resources/application-test.yml` to stub `spring.security.oauth2.resourceserver.jwt.*` so no Keycloak is needed. Authenticated requests use `SecurityMockMvcRequestPostProcessors.jwt()`.
- Boot 4 test conventions: `@SpringBootTest` no longer provides `MockMvc`/`TestRestTemplate`. Add `@AutoConfigureMockMvc` (`org.springframework.boot.webmvc.test.autoconfigure`) and, for `TestRestTemplate` (`org.springframework.boot.resttestclient`), `@AutoConfigureTestRestTemplate`. Use `@MockitoBean`, not `@MockBean`.
- Unit tests that need a mapper use `JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build()` to match `application.yml`.
- `LayersJsonGoldenTest` + `src/test/resources/golden/` pin the on-disk `layers.json` format written by 1.x. Do not regenerate the golden file to make it pass.

### Jackson 3 gotchas

- Packages are `tools.jackson.*`; only `jackson-annotations` stays `com.fasterxml.jackson.annotation`. Mapper type is `JsonMapper`; `JacksonException` is unchecked.
- Stores/import-export wrap `JacksonException` as `IOException` (`JsonFileStore`, `ImportExportService`). Keep that: callers' `catch (IOException)` implements the corrupt-file recovery (e.g. `TileInventoryStore.init`).
- Layer records carry explicit `@JsonProperty` on every component. Jackson 3 links a component to its `@JsonIgnore` legacy alias getter in `Layer` (`getId()` etc.) and drops it on read otherwise. Annotate any new component.
- Jackson 3 ignores unknown JSON fields and rejects null for primitives by default. `spring.jackson.deserialization.fail-on-null-for-primitives=false` in `application.yml` keeps old files with omitted numbers loading.

## Authentication

Write endpoints (`POST/PUT/DELETE`) require a bearer token with the realm role configured by `xyz.adminRole` (default `admin`). GET endpoints are anonymous-friendly: per-layer `allowedUsers` / `allowedGroups` (empty = public) gate reads. Admins bypass per-layer ACL.

- `Layer.allowedUsers` / `Layer.allowedGroups` — persisted in `layers.json`; settable via `POST /layers`, `PUT /layers/{name}`.
- `LayerAccessService` — single source of truth for read decisions; used by tile endpoints and `GET /layers` (filters by access).
- `SecurityConfig` — Spring Security filter chain. Maps Keycloak `realm_access.roles` → `ROLE_*`; group ACL pulls from the `groups` JWT claim.

### Auth modes (`xyz.auth.mode`)

- `jwt` (default) — Keycloak resource-server flow. Set `KEYCLOAK_ISSUER_URI`.
- `token` — single static token in `xyz.auth.adminToken` presented as `Authorization: Bearer <token>`. Treated as admin. Per-layer ACLs reduce to public + admin (no per-user identity). Leave `KEYCLOAK_ISSUER_URI` unset (default empty so Spring skips `JwtDecoder` autoconfig).

### Local Keycloak

```bash
docker compose up -d keycloak              # :8080, imports realm
bash scripts/keycloak/setup.sh             # waits + prints token-fetch curls
bash scripts/keycloak/setup.sh --reseed    # delete + re-import
```

Test users (password `password`) are seeded by the realm import; `setup.sh` prints them. Override the issuer per env with `KEYCLOAK_ISSUER_URI` (default `http://localhost:8080/realms/xyz-tile-cache`).
