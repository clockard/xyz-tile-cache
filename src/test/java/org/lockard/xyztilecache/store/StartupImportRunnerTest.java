package org.lockard.xyztilecache.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
class StartupImportRunnerTest {

  @TempDir static File tileDir;

  @TempDir static File importDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.importDirectory", () -> importDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          LayerProperties pub = new LayerProperties();
          pub.setName("startup-public");
          pub.setUrlTemplate("https://example.com/pub/{z}/{y}/{x}");
          pub.setMaxZoom(5);
          return List.of(pub);
        });
  }

  @Autowired StartupImportRunner runner;
  @Autowired LayerStore layerStore;
  @Autowired ObjectMapper objectMapper;
  @Autowired XyzConfiguration configuration;

  private Path trackingFile;

  @BeforeEach
  void cleanImportDir() throws Exception {
    trackingFile = importDir.toPath().resolve(".imported");
    Files.deleteIfExists(trackingFile);
    try (var paths = Files.list(importDir.toPath())) {
      paths
          .filter(p -> p.getFileName().toString().endsWith(".zip"))
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (Exception ignored) {
                }
              });
    }
  }

  @Test
  void runStartupImports_emptyDirectory_doesNothing() {
    runner.runStartupImports();
    assertThat(trackingFile).doesNotExist();
  }

  @Test
  void runStartupImports_newZip_importsLayerAndTiles() throws Exception {
    LayerProperties layer = new LayerProperties();
    layer.setName("startup-new-a");
    layer.setUrlTemplate("https://example.com/a/{z}/{y}/{x}");
    layer.setMaxZoom(4);

    byte[] zip =
        buildZip(
            "startup-new-a/layer.json",
            objectMapper.writeValueAsBytes(layer),
            "startup-new-a/2/3/4.png",
            new byte[] {42});
    Files.write(importDir.toPath().resolve("bundle-a.zip"), zip);

    runner.runStartupImports();

    assertThat(layerStore.getLayer("startup-new-a")).isPresent();
    Path tile =
        tileDir.toPath().resolve("startup-new-a").resolve("2").resolve("3").resolve("4.png");
    assertThat(Files.readAllBytes(tile)).containsExactly(42);
    assertThat(Files.readAllLines(trackingFile)).contains("bundle-a.zip");
  }

  @Test
  void runStartupImports_alreadyTracked_skipsFile() throws Exception {
    LayerProperties layer = new LayerProperties();
    layer.setName("startup-skip-b");
    layer.setUrlTemplate("https://example.com/b/{z}/{y}/{x}");
    layer.setMaxZoom(4);

    byte[] zip = buildZip("startup-skip-b/layer.json", objectMapper.writeValueAsBytes(layer));
    Files.write(importDir.toPath().resolve("bundle-b.zip"), zip);
    Files.writeString(trackingFile, "bundle-b.zip\n", StandardCharsets.UTF_8);

    runner.runStartupImports();

    assertThat(layerStore.getLayer("startup-skip-b")).isEmpty();
  }

  @Test
  void runStartupImports_multipleZips_onlyNewOnesProcessed() throws Exception {
    LayerProperties layerC = new LayerProperties();
    layerC.setName("startup-multi-c");
    layerC.setUrlTemplate("https://example.com/c/{z}/{y}/{x}");
    layerC.setMaxZoom(4);

    LayerProperties layerD = new LayerProperties();
    layerD.setName("startup-multi-d");
    layerD.setUrlTemplate("https://example.com/d/{z}/{y}/{x}");
    layerD.setMaxZoom(4);

    Files.write(
        importDir.toPath().resolve("bundle-c.zip"),
        buildZip("startup-multi-c/layer.json", objectMapper.writeValueAsBytes(layerC)));
    Files.write(
        importDir.toPath().resolve("bundle-d.zip"),
        buildZip("startup-multi-d/layer.json", objectMapper.writeValueAsBytes(layerD)));

    Files.writeString(trackingFile, "bundle-c.zip\n", StandardCharsets.UTF_8);

    runner.runStartupImports();

    assertThat(layerStore.getLayer("startup-multi-c")).isEmpty();
    assertThat(layerStore.getLayer("startup-multi-d")).isPresent();
    List<String> tracked = Files.readAllLines(trackingFile);
    assertThat(tracked).contains("bundle-c.zip", "bundle-d.zip");
  }

  @Test
  void runStartupImports_nonExistentDirectory_doesNotThrow() {
    String original = configuration.getImportDirectory();
    configuration.setImportDirectory("/no/such/path/xyz-imports");
    try {
      runner.runStartupImports();
    } finally {
      configuration.setImportDirectory(original);
    }
  }

  @Test
  void runStartupImports_trackingFileUpdatedAfterEachZip() throws Exception {
    LayerProperties layerE = new LayerProperties();
    layerE.setName("startup-tracking-e");
    layerE.setUrlTemplate("https://example.com/e/{z}/{y}/{x}");
    layerE.setMaxZoom(4);

    Files.write(
        importDir.toPath().resolve("bundle-e.zip"),
        buildZip("startup-tracking-e/layer.json", objectMapper.writeValueAsBytes(layerE)));

    runner.runStartupImports();
    assertThat(Files.readAllLines(trackingFile)).contains("bundle-e.zip");

    runner.runStartupImports();
    assertThat(layerStore.getLayer("startup-tracking-e")).isPresent();
  }

  @Test
  void runStartupImports_zipWithPmtilesInLayerDir_importsCorrectly() throws Exception {
    LayerProperties layer = new LayerProperties();
    layer.setName("startup-vector-layer");
    layer.setSourceType(Layer.SourceType.VECTOR_PMTILES);
    layer.setUrlTemplate(tileDir.getAbsolutePath() + "/startup-vector-layer/basemap.pmtiles");
    layer.setMaxZoom(14);

    byte[] zip =
        buildZip(
            "startup-vector-layer/layer.json",
            objectMapper.writeValueAsBytes(layer),
            "startup-vector-layer/basemap.pmtiles",
            pmtilesFixture());
    Files.write(importDir.toPath().resolve("vector-bundle.zip"), zip);

    runner.runStartupImports();

    assertThat(layerStore.getLayer("startup-vector-layer")).isPresent();
    assertThat(tileDir.toPath().resolve("startup-vector-layer").resolve("basemap.pmtiles"))
        .exists();
    assertThat(Files.readAllLines(trackingFile)).contains("vector-bundle.zip");
  }

  @Test
  void runStartupImports_barePmtilesFile_isIgnored() throws Exception {
    Files.write(importDir.toPath().resolve("bare.pmtiles"), pmtilesFixture());

    runner.runStartupImports();

    assertThat(trackingFile).doesNotExist();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static byte[] pmtilesFixture() throws Exception {
    URL url = StartupImportRunnerTest.class.getClassLoader().getResource("test_fixture_1.pmtiles");
    assertThat(url).isNotNull();
    return Files.readAllBytes(Paths.get(url.toURI()));
  }

  private static byte[] buildZip(String name, byte[] content) throws Exception {
    return buildZip(name, content, null, null);
  }

  private static byte[] buildZip(String name1, byte[] content1, String name2, byte[] content2)
      throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      zos.putNextEntry(new ZipEntry(name1));
      zos.write(content1);
      zos.closeEntry();
      if (name2 != null) {
        zos.putNextEntry(new ZipEntry(name2));
        zos.write(content2);
        zos.closeEntry();
      }
    }
    return bos.toByteArray();
  }
}
