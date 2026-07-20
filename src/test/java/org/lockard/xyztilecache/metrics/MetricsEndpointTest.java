package org.lockard.xyztilecache.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class MetricsEndpointTest {

  @TempDir static File tileDir;

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties osm = new LayerProperties();
          osm.setId("osm");
          osm.setName("OpenStreetMap");
          osm.setSourceType(Layer.SourceType.XYZ);
          osm.setUrlTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
          osm.setMaxZoom(19);
          return java.util.List.of(osm);
        });
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  void prometheusEndpoint_anonymous_isUnauthorized() throws Exception {
    // Metrics carry per-layer identifiers; they must not be readable without the admin role.
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void prometheusEndpoint_admin_exposesTileCacheMetrics() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/actuator/prometheus")
                    .with(adminJwt())
                    .accept(MediaType.valueOf("text/plain")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andReturn();
    String body = result.getResponse().getContentAsString();

    // Per-layer gauges
    assertThat(body).contains(TileCacheMetrics.CACHED_TILES);
    assertThat(body).contains(TileCacheMetrics.CACHED_BYTES);
    assertThat(body).contains(TileCacheMetrics.TILES_SERVED);
    assertThat(body).contains(TileCacheMetrics.BREAKER_STATE);
    assertThat(body).contains("layer=\"osm\"");

    // Caffeine cache metrics (CaffeineCacheMetrics.monitor wires hit/miss counters)
    assertThat(body).contains("xyz_tile_cache");
    assertThat(body).contains("cache=\"xyz_tile_cache\"");

    // In-flight preload gauge
    assertThat(body).contains("xyz_preload_inflight");
  }

  @Test
  void healthEndpoint_isPublic() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void perLayerGaugesAreRegisteredAtStartup() {
    assertThat(meterRegistry.find(TileCacheMetrics.CACHED_TILES).tag("layer", "osm").gauge())
        .isNotNull();
    assertThat(meterRegistry.find(TileCacheMetrics.BREAKER_STATE).tag("layer", "osm").gauge())
        .isNotNull();
  }
}
