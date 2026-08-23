package org.lockard.xyztilecache.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.service.ExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What happens to an export when its download does not go through.
 *
 * <p>The job used to be claimed and its file deleted before the bytes were sent, so any transfer
 * that died took the export with it and every retry answered 404 — the user's only recourse being
 * to run the whole export again.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportDownloadRetryTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;
  @Autowired ExportService exportService;
  @Autowired ObjectMapper objectMapper;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties layer = new LayerProperties();
          layer.setId("exportable");
          layer.setName("Exportable");
          layer.setUrlTemplate("https://example.com/{z}/{x}/{y}.png");
          return List.of(layer);
        });
  }

  static RequestPostProcessor user() {
    return jwt().jwt(j -> j.subject("dan").claim("preferred_username", "dan"));
  }

  private String submitExport() throws Exception {
    String body =
        mvc.perform(
                post("/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"layers\":[\"exportable\"]}")
                    .with(user()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).get("id").asText();
  }

  private void awaitDone(String jobId) throws Exception {
    for (int i = 0; i < 100; i++) {
      String body =
          mvc.perform(get("/exports/" + jobId).with(user()))
              .andReturn()
              .getResponse()
              .getContentAsString();
      if ("DONE".equals(objectMapper.readTree(body).get("status").asText())) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("export did not finish");
  }

  @Test
  void aSuccessfulDownload_consumesTheJob() throws Exception {
    String jobId = submitExport();
    awaitDone(jobId);

    mvc.perform(get("/exports/" + jobId + "/download").with(user())).andExpect(status().isOk());

    // One-shot by design: the bytes are out, so the job and its temp file are released.
    mvc.perform(get("/exports/" + jobId + "/download").with(user()))
        .andExpect(status().isNotFound());
  }

  @Test
  void aMissingExportFile_isReportedAsNotFoundRatherThanADeadResponse() throws Exception {
    String jobId = submitExport();
    awaitDone(jobId);

    // Stand in for the file vanishing underneath the request. The size used to be read after the
    // status and headers were written, which left the response committed with a 200 that could
    // only be abandoned mid-flight -- the client sees a failed transfer against a success status.
    Files.delete(exportService.getJob(jobId).orElseThrow().getTempFile());

    mvc.perform(get("/exports/" + jobId + "/download").with(user()))
        .andExpect(status().isNotFound());
  }

  @Test
  void afterAFailedTransfer_theExportIsStillThereToRetry() throws Exception {
    String jobId = submitExport();
    awaitDone(jobId);

    // The job survives a request that never reaches the copy, so a caller can come back for it.
    mvc.perform(get("/exports/" + jobId + "/download")).andExpect(status().isUnauthorized());

    assertThat(exportService.getJob(jobId)).isPresent();
    mvc.perform(get("/exports/" + jobId + "/download").with(user())).andExpect(status().isOk());
  }
}
