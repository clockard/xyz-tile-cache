package org.lockard.xyztilecache.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.store.LayerStore;
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
class LayerControllerExceptionTest {

  @TempDir static File tileDir;

  @MockBean LayerStore layerStore;

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

  @Test
  void addLayer_storeThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    doThrow(new IOException("disk full")).when(layerStore).addLayer(any(Layer.class));

    mvc.perform(
            post("/layers")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"io-fail\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void updateLayer_storeThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    doThrow(new IOException("disk full"))
        .when(layerStore)
        .updateLayer(anyString(), any(Layer.class));

    mvc.perform(
            put("/layers/some-id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"io-fail\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void updateLayer_storeThrowsIllegalArgument_returns400(@Autowired MockMvc mvc) throws Exception {
    doThrow(new IllegalArgumentException("id mismatch"))
        .when(layerStore)
        .updateLayer(anyString(), any(Layer.class));

    mvc.perform(
            put("/layers/some-id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"io-fail\",\"urlTemplate\":\"https://t.co/{z}/{x}/{y}.png\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteLayer_storeThrowsIOException_returns500(@Autowired MockMvc mvc) throws Exception {
    doThrow(new IOException("disk full")).when(layerStore).removeLayer(anyString());

    mvc.perform(delete("/layers/some-id").with(adminJwt()))
        .andExpect(status().isInternalServerError());
  }
}
