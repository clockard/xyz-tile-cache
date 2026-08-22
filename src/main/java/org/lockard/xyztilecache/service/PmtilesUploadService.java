package org.lockard.xyztilecache.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.pmtiles.PmtilesReader;
import org.lockard.xyztilecache.pmtiles.PmtilesTileType;
import org.lockard.xyztilecache.store.TileInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Takes uploaded PMTiles (or MBTiles) files and installs them as a layer's archives.
 *
 * <p>Everything is staged and validated in a temporary directory first, so a rejected upload never
 * leaves a partial or mismatched archive in a layer's directory. Only once every file has been
 * proven readable and of the layer's tile type are they moved into place.
 */
@Service
public class PmtilesUploadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PmtilesUploadService.class);

  private static final byte[] PMTILES_MAGIC = {'P', 'M', 'T', 'i', 'l', 'e', 's'};

  /** SQLite's 16-byte file header: the ASCII text plus its terminating NUL. */
  private static final byte[] SQLITE_MAGIC = sqliteMagic();

  private static final int MAX_ARCHIVE_NAME = 64;

  private static byte[] sqliteMagic() {
    byte[] text = "SQLite format 3".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] magic = java.util.Arrays.copyOf(text, text.length + 1);
    magic[text.length] = 0;
    return magic;
  }

  private final XyzConfiguration configuration;
  private final MbtilesConverter mbtilesConverter;
  private final PmtilesManager pmtilesManager;
  private final TileInventoryStore inventory;

  public PmtilesUploadService(
      XyzConfiguration configuration,
      MbtilesConverter mbtilesConverter,
      PmtilesManager pmtilesManager,
      TileInventoryStore inventory) {
    this.configuration = configuration;
    this.mbtilesConverter = mbtilesConverter;
    this.pmtilesManager = pmtilesManager;
    this.inventory = inventory;
  }

  /** A staged upload: validated, named, and ready to move into a layer directory. */
  public record StagedArchive(
      String fileName, Path path, PmtilesTileType tileType, long bytes, int maxZoom) {}

  /** Rejected before anything was written into the layer; the message is safe to show a user. */
  public static class UploadRejectedException extends RuntimeException {
    public UploadRejectedException(String message) {
      super(message);
    }
  }

  /** The {@code pmtiles} CLI is needed for this upload but is not installed. */
  public static class ConverterUnavailableException extends RuntimeException {
    public ConverterUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * Validates uploads and installs them under {@code layerId}.
   *
   * <p>{@code existingType} is the type the layer already serves, if it has one. Every uploaded
   * archive must match it, and each other: a layer carries a single tile type.
   *
   * @return the installed archives
   */
  public List<StagedArchive> install(
      String layerId, List<MultipartFile> files, Optional<PmtilesTileType> existingType)
      throws IOException {
    if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
      throw new UploadRejectedException("At least one .pmtiles or .mbtiles file is required.");
    }
    requireDiskSpace();

    Path staging = createStagingDirectory();
    try {
      List<StagedArchive> staged = new ArrayList<>();
      int index = 0;
      for (MultipartFile file : files) {
        if (file.isEmpty()) {
          continue;
        }
        staged.add(stage(file, staging, index++));
      }
      PmtilesTileType layerType = requireSingleTileType(staged, existingType);
      return moveIntoLayer(layerId, staged, layerType);
    } finally {
      deleteRecursively(staging);
    }
  }

  /**
   * Creates the staging directory <em>inside</em> the tile directory rather than in the system temp
   * space.
   *
   * <p>The tile directory is typically a mounted volume, so staging elsewhere puts the validated
   * archive on a different filesystem and the move into place becomes a cross-device copy that
   * cannot be atomic. Staging alongside the destination keeps the final move a rename. The leading
   * dot also keeps the directory from being mistaken for a layer: layer ids must start with a
   * letter or digit, so the inventory scanner skips it.
   */
  private Path createStagingDirectory() throws IOException {
    Path parent = Path.of(configuration.getBaseTileDirectory(), ".uploads");
    Files.createDirectories(parent);
    return Files.createTempDirectory(parent, "staging-");
  }

  /** Saves one upload, converting MBTiles as needed, and reads back what it holds. */
  private StagedArchive stage(MultipartFile file, Path staging, int index) throws IOException {
    String originalName =
        file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
    Path raw = staging.resolve("raw-" + index);
    try (InputStream in = file.getInputStream()) {
      Files.copy(in, raw, StandardCopyOption.REPLACE_EXISTING);
    }

    Path archive;
    if (startsWith(raw, PMTILES_MAGIC)) {
      archive = raw;
    } else if (startsWith(raw, SQLITE_MAGIC)) {
      archive = staging.resolve("converted-" + index + ".pmtiles");
      try {
        mbtilesConverter.convert(raw, archive);
      } catch (MbtilesConverter.ConverterUnavailableException e) {
        throw new ConverterUnavailableException(
            "This instance cannot convert MBTiles: the 'pmtiles' command is not installed."
                + " Upload a .pmtiles file instead.");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while converting " + originalName, e);
      } catch (IOException e) {
        LOGGER.warn("MBTiles conversion failed for '{}'", originalName, e);
        throw new UploadRejectedException(conversionFailureMessage(originalName, e.getMessage()));
      }
    } else {
      // Judged by content, not by the extension the browser happened to send.
      throw new UploadRejectedException(
          "'" + originalName + "' is neither a PMTiles archive nor an MBTiles database.");
    }

    PmtilesTileType tileType;
    int maxZoom;
    try (PmtilesReader reader = new PmtilesReader(archive)) {
      tileType = reader.tileType();
      // The header knows how deep the archive goes; a layer configured beyond that would have the
      // map requesting zoom levels that hold nothing.
      maxZoom = reader.getHeader().maxZoom();
    } catch (IOException | RuntimeException e) {
      throw new UploadRejectedException(
          "'" + originalName + "' is not a readable PMTiles archive: " + e.getMessage());
    }
    return new StagedArchive(
        archiveName(originalName), archive, tileType, Files.size(archive), maxZoom);
  }

  /**
   * Turns the CLI's output into something a user can act on.
   *
   * <p>The raw text names the staging file rather than theirs and leads with Go stack noise, so a
   * recognised cause is reported plainly and anything else is passed through with the internal
   * paths stripped. The full output is in the log either way.
   */
  private static String conversionFailureMessage(String originalName, String cliOutput) {
    String output = cliOutput == null ? "" : cliOutput;
    if (output.contains("database disk image is malformed")
        || output.contains("file is not a database")) {
      return "'%s' could not be read as an MBTiles database. The file looks incomplete or"
              .formatted(originalName)
          + " corrupted — check that it downloaded fully.";
    }
    if (output.contains("no such table")) {
      return "'%s' is a database but not an MBTiles archive: it has no tiles table."
          .formatted(originalName);
    }
    // Drop absolute paths so an error message never exposes the server's layout.
    String cleaned = output.replaceAll("/\\S*/(staging-\\S*|raw-\\d+)", "the uploaded file");
    return "Could not convert '%s': %s".formatted(originalName, cleaned);
  }

  /** Every archive in a layer holds the same kind of tile; this is where that is decided. */
  private PmtilesTileType requireSingleTileType(
      List<StagedArchive> staged, Optional<PmtilesTileType> existingType) {
    PmtilesTileType layerType = existingType.orElseGet(() -> staged.getFirst().tileType());
    for (StagedArchive archive : staged) {
      if (archive.tileType() != layerType) {
        throw new UploadRejectedException(
            ("'%s' holds %s tiles but the layer serves %s. A layer carries one tile type;"
                    + " upload this archive to its own layer.")
                .formatted(archive.fileName(), archive.tileType(), layerType));
      }
    }
    return layerType;
  }

  /** Moves validated archives into the layer directory and registers them for serving. */
  private List<StagedArchive> moveIntoLayer(
      String layerId, List<StagedArchive> staged, PmtilesTileType layerType) throws IOException {
    Path layerDir = Path.of(configuration.getBaseTileDirectory(), layerId).normalize();
    Files.createDirectories(layerDir);

    List<StagedArchive> installed = new ArrayList<>();
    for (StagedArchive archive : staged) {
      Path target = layerDir.resolve(archive.fileName()).normalize();
      if (!target.startsWith(layerDir)) {
        throw new UploadRejectedException("Invalid archive name: " + archive.fileName());
      }
      if (Files.exists(target)) {
        throw new UploadRejectedException(
            ("Layer '%s' already has an archive named '%s'. Rename the file or remove the"
                    + " existing one first.")
                .formatted(layerId, archive.fileName()));
      }
      moveIntoPlace(archive.path(), target);
      // The archive is one file holding many tiles: bytes count, tile count does not.
      inventory.recordWrite(layerId, 0, Files.size(target));
      installed.add(
          new StagedArchive(
              archive.fileName(), target, archive.tileType(), archive.bytes(), archive.maxZoom()));
      LOGGER.info(
          "Installed {} archive '{}' ({} bytes) for layer '{}'",
          layerType,
          archive.fileName(),
          archive.bytes(),
          layerId);
    }
    installed.forEach(a -> pmtilesManager.notifyFileAvailable(a.path()));
    return installed;
  }

  /**
   * Renames the staged archive into the layer directory, falling back to a plain move if the
   * filesystem cannot do it atomically — staging shares the destination's volume, so that should
   * not happen, but an unusual mount should not fail the upload.
   */
  private static void moveIntoPlace(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      LOGGER.debug("Atomic move unavailable for {}; copying instead", to);
      Files.move(from, to);
    }
  }

  private void requireDiskSpace() throws IOException {
    Path baseDir = Path.of(configuration.getBaseTileDirectory());
    Files.createDirectories(baseDir);
    if (Files.getFileStore(baseDir).getUsableSpace() < configuration.getMinFreeDiskBytes()) {
      throw new UploadRejectedException("Not enough free disk space to accept this upload.");
    }
  }

  /**
   * Derives a safe archive file name from whatever the browser sent: directory components dropped,
   * unusable characters replaced, and a {@code .pmtiles} extension regardless of the input format.
   */
  static String archiveName(String originalName) {
    String base = originalName.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      base = base.substring(0, dot);
    }
    base = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    base = base.replaceAll("^[^a-z0-9]+", "");
    if (base.isBlank()) {
      base = "archive";
    }
    if (base.length() > MAX_ARCHIVE_NAME) {
      base = base.substring(0, MAX_ARCHIVE_NAME);
    }
    return base + ".pmtiles";
  }

  private static boolean startsWith(Path file, byte[] magic) throws IOException {
    byte[] head = new byte[magic.length];
    try (InputStream in = Files.newInputStream(file)) {
      if (in.readNBytes(head, 0, magic.length) < magic.length) {
        return false;
      }
    }
    return java.util.Arrays.equals(head, magic);
  }

  private static void deleteRecursively(Path root) {
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOGGER.debug("Could not delete staging file {}", p);
                }
              });
    } catch (IOException e) {
      LOGGER.warn("Could not clean staging directory {}", root, e);
    }
  }
}
