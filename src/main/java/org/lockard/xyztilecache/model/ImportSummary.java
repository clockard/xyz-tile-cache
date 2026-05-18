package org.lockard.xyztilecache.model;

import java.util.List;

public record ImportSummary(
    List<String> layersAdded, List<String> layersSkipped, long tilesWritten, long pmtilesImported) {

  public ImportSummary(List<String> layersAdded, List<String> layersSkipped, long tilesWritten) {
    this(layersAdded, layersSkipped, tilesWritten, 0L);
  }
}
