package org.lockard.xyztilecache;

import java.util.List;

public record ImportSummary(
    List<String> layersAdded, List<String> layersSkipped, long tilesWritten) {}
