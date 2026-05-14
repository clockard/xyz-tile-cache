package org.lockard.xyztilecache;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class VectorTileControllerMockTest {

  @TempDir static File tileDir;

  @MockBean VectorTileService vectorTileService;
  @MockBean PreloadService preloadService;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", () -> List.of());
    registry.add("xyz.vector.downloadDirectory", () -> tileDir.getAbsolutePath() + "/vec");
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  @Test
  void getTile_serviceThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    when(vectorTileService.getTile(anyInt(), anyInt(), anyInt()))
        .thenThrow(new IOException("disk error"));

    mvc.perform(get("/vector/0/0/0")).andExpect(status().isInternalServerError());
  }

  @Test
  void postPreload_serviceThrowsIllegalArgument_returns503(@Autowired MockMvc mvc)
      throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalArgumentException("source unavailable"));

    mvc.perform(
            post("/vector/preload")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":5}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void postPreload_serviceThrowsIllegalState_returns409(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalStateException("already in progress"));

    mvc.perform(
            post("/vector/preload")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":5}"))
        .andExpect(status().isConflict());
  }

  @Test
  void postPreload_serviceThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any()))
        .thenThrow(new IOException("persist failed"));

    mvc.perform(
            post("/vector/preload")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":5}"))
        .andExpect(status().isInternalServerError());
  }
}
