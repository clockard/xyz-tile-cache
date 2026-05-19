package org.lockard.xyztilecache.controller;

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
import org.lockard.xyztilecache.service.PreloadService;
import org.lockard.xyztilecache.store.PreloadStore;
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
class PreloadControllerExceptionTest {

  @TempDir static File tileDir;

  @MockBean PreloadService preloadService;
  @MockBean PreloadStore preloadStore;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.layers", () -> List.of());
  }

  static RequestPostProcessor adminJwt() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("preferred_username", "alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static String validRequest() {
    return "{\"boundingBox\":{\"north\":41,\"south\":40,\"east\":-73,\"west\":-74},\"maxZoom\":5}";
  }

  @Test
  void create_submitReturnsNull_returns400(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any(), any()))
        .thenReturn(null);

    mvc.perform(
            post("/preloads")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_submitThrowsIllegalArgument_returns503(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("source unavailable"));

    mvc.perform(
            post("/preloads")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void create_submitThrowsIllegalState_returns409(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any(), any()))
        .thenThrow(new IllegalStateException("already in progress"));

    mvc.perform(
            post("/preloads")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isConflict());
  }

  @Test
  void create_submitThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    when(preloadService.submit(any(), any(), anyInt(), any(), anyBoolean(), any(), any(), any()))
        .thenThrow(new IOException("persist failed"));

    mvc.perform(
            post("/preloads")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void delete_storeThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    doThrow(new IOException("disk error")).when(preloadStore).removePreload(anyString());

    mvc.perform(delete("/preloads/some-id").with(adminJwt()))
        .andExpect(status().isInternalServerError());
  }
}
