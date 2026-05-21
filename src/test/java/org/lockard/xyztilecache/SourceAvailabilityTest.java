package org.lockard.xyztilecache;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.util.Collection;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("source-check")
class SourceAvailabilityTest {

  @TempDir static File tileDir;

  @Autowired MockMvc mvc;
  @Autowired XyzConfiguration config;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", tileDir::getAbsolutePath);
    registry.add("xyz.tileTimeoutSeconds", () -> 15);
  }

  @TestFactory
  Collection<DynamicTest> allSourcesServeTiles() {
    return config.getLayers().keySet().stream()
        .map(
            name ->
                DynamicTest.dynamicTest(
                    name,
                    () ->
                        mvc.perform(get("/tilesZYX/{layer}/1/0/1.png", name))
                            .andExpect(status().isOk())
                            .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))))
        .toList();
  }
}
