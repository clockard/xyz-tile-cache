package org.lockard.xyztilecache;

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

  @TempDir static File vectorDir;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    registry.add("xyz.baseTileDirectory", () -> tileDir.getAbsolutePath());
    registry.add("xyz.importDirectory", () -> importDir.getAbsolutePath());
    registry.add("xyz.vector.downloadDirectory", () -> vectorDir.getAbsolutePath());
    registry.add(
        "xyz.layers",
        () -> {
          Layer pub = new Layer();
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
  @Autowired VectorConfiguration vectorConfiguration;

  private Path trackingFile;

  @BeforeEach
  void cleanImportDir() throws Exception {
    trackingFile = importDir.toPath().resolve(".imported");
    Files.deleteIfExists(trackingFile);
    try (var paths = Files.list(importDir.toPath())) {
      paths
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".zip") || name.endsWith(".pmtiles");
              })
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
    Layer layer = new Layer();
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
    Layer layer = new Layer();
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
    Layer layerC = new Layer();
    layerC.setName("startup-multi-c");
    layerC.setUrlTemplate("https://example.com/c/{z}/{y}/{x}");
    layerC.setMaxZoom(4);

    Layer layerD = new Layer();
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
    Layer layerE = new Layer();
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
  void runStartupImports_pmtilesZip_extractsAndRegisters() throws Exception {
    byte[] zip = buildZip("region.pmtiles", pmtilesFixture());
    Files.write(importDir.toPath().resolve("vector-a.zip"), zip);

    runner.runStartupImports();

    assertThat(vectorDir.toPath().resolve("region.pmtiles")).exists();
    assertThat(Files.readAllLines(trackingFile)).contains("vector-a.zip");
  }

  @Test
  void runStartupImports_mixedZip_handlesBoth() throws Exception {
    Layer layer = new Layer();
    layer.setName("startup-mixed-layer");
    layer.setUrlTemplate("https://example.com/x/{z}/{y}/{x}");
    layer.setMaxZoom(4);

    byte[] zip =
        buildZip(
            "startup-mixed-layer/layer.json",
            objectMapper.writeValueAsBytes(layer),
            "region-mixed.pmtiles",
            pmtilesFixture());
    Files.write(importDir.toPath().resolve("mixed.zip"), zip);

    runner.runStartupImports();

    assertThat(layerStore.getLayer("startup-mixed-layer")).isPresent();
    assertThat(vectorDir.toPath().resolve("region-mixed.pmtiles")).exists();
    assertThat(Files.readAllLines(trackingFile)).contains("mixed.zip");
  }

  @Test
  void runStartupImports_pmtilesZip_noDownloadDir_skips() throws Exception {
    String original = vectorConfiguration.getDownloadDirectory();
    vectorConfiguration.setDownloadDirectory("");
    try {
      byte[] zip = buildZip("mymap.pmtiles", new byte[] {1, 2, 3});
      Files.write(importDir.toPath().resolve("vector-skip.zip"), zip);

      runner.runStartupImports();

      assertThat(vectorDir.toPath().resolve("mymap.pmtiles")).doesNotExist();
      assertThat(Files.readAllLines(trackingFile)).contains("vector-skip.zip");
    } finally {
      vectorConfiguration.setDownloadDirectory(original);
    }
  }

  @Test
  void runStartupImports_barePmtilesFile_copiesAndRegisters() throws Exception {
    Files.write(importDir.toPath().resolve("bare.pmtiles"), pmtilesFixture());

    runner.runStartupImports();

    assertThat(vectorDir.toPath().resolve("bare.pmtiles")).exists();
    assertThat(Files.readAllLines(trackingFile)).contains("bare.pmtiles");
  }

  @Test
  void runStartupImports_barePmtilesFile_alreadyTracked_skips() throws Exception {
    Files.write(importDir.toPath().resolve("tracked.pmtiles"), pmtilesFixture());
    Files.writeString(trackingFile, "tracked.pmtiles\n", StandardCharsets.UTF_8);

    runner.runStartupImports();

    assertThat(vectorDir.toPath().resolve("tracked.pmtiles")).doesNotExist();
  }

  @Test
  void runStartupImports_barePmtilesFile_noDownloadDir_skipsAndDoesNotTrack() throws Exception {
    String original = vectorConfiguration.getDownloadDirectory();
    vectorConfiguration.setDownloadDirectory("");
    try {
      Files.write(importDir.toPath().resolve("untracked.pmtiles"), pmtilesFixture());

      runner.runStartupImports();

      assertThat(vectorDir.toPath().resolve("untracked.pmtiles")).doesNotExist();
      assertThat(trackingFile).doesNotExist();
    } finally {
      vectorConfiguration.setDownloadDirectory(original);
    }
  }

  // ── init zoom world download ──────────────────────────────────────────────

  @Test
  void runStartupImports_initZoomZero_doesNotTriggerDownload() throws Exception {
    int original = vectorConfiguration.getInitZoom();
    vectorConfiguration.setInitZoom(0);
    try {
      runner.runStartupImports();
      // world_z0-0.pmtiles should not appear
      assertThat(vectorDir.toPath().resolve("world_z0-0.pmtiles")).doesNotExist();
    } finally {
      vectorConfiguration.setInitZoom(original);
    }
  }

  @Test
  void runStartupImports_initZoomSet_fileAlreadyExists_skipsDownload() throws Exception {
    int original = vectorConfiguration.getInitZoom();
    vectorConfiguration.setInitZoom(5);
    // Pre-create the target file to simulate a previous download
    Files.write(vectorDir.toPath().resolve("world_z0-5.pmtiles"), new byte[] {1, 2, 3});
    try {
      runner.runStartupImports();
      // File exists unchanged (download was skipped, not re-triggered)
      assertThat(Files.readAllBytes(vectorDir.toPath().resolve("world_z0-5.pmtiles")))
          .containsExactly(1, 2, 3);
    } finally {
      vectorConfiguration.setInitZoom(original);
      Files.deleteIfExists(vectorDir.toPath().resolve("world_z0-5.pmtiles"));
    }
  }

  @Test
  void runStartupImports_initZoomSet_noDownloadDir_logsAndSkips() throws Exception {
    int origZoom = vectorConfiguration.getInitZoom();
    String origDir = vectorConfiguration.getDownloadDirectory();
    vectorConfiguration.setInitZoom(3);
    vectorConfiguration.setDownloadDirectory("");
    try {
      runner.runStartupImports();
      // No exception, no file written
      assertThat(vectorDir.toPath().resolve("world_z0-3.pmtiles")).doesNotExist();
    } finally {
      vectorConfiguration.setInitZoom(origZoom);
      vectorConfiguration.setDownloadDirectory(origDir);
    }
  }

  @Test
  void runStartupImports_initZoomSet_sourceUrlBlank_skipsDownload() throws Exception {
    // application-test.yml sets sourceUrl="" so we just need initZoom > 0 with downloadDir set
    int origZoom = vectorConfiguration.getInitZoom();
    vectorConfiguration.setInitZoom(4);
    try {
      runner.runStartupImports();
      assertThat(vectorDir.toPath().resolve("world_z0-4.pmtiles")).doesNotExist();
    } finally {
      vectorConfiguration.setInitZoom(origZoom);
    }
  }

  @Test
  void runStartupImports_vectorDisabled_skipsDownload() throws Exception {
    int origZoom = vectorConfiguration.getInitZoom();
    boolean origEnabled = vectorConfiguration.isEnabled();
    vectorConfiguration.setInitZoom(6);
    vectorConfiguration.setEnabled(false);
    try {
      runner.runStartupImports();
      assertThat(vectorDir.toPath().resolve("world_z0-6.pmtiles")).doesNotExist();
    } finally {
      vectorConfiguration.setInitZoom(origZoom);
      vectorConfiguration.setEnabled(origEnabled);
    }
  }

  @Test
  void runStartupImports_barePmtilesFile_unsafeName_skipsAndDoesNotTrack() throws Exception {
    // Name contains a space — fails SAFE_PMTILES_NAME pattern
    java.nio.file.Path unsafeFile = importDir.toPath().resolve("bad file.pmtiles");
    Files.write(unsafeFile, pmtilesFixture());
    try {
      runner.runStartupImports();
      assertThat(vectorDir.toPath().resolve("bad file.pmtiles")).doesNotExist();
      assertThat(trackingFile).doesNotExist();
    } finally {
      Files.deleteIfExists(unsafeFile);
    }
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
