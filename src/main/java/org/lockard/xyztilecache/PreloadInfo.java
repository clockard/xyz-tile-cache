package org.lockard.xyztilecache;

import java.time.Instant;
import java.util.List;

record PreloadInfo(
    String id,
    String name,
    BoundingBox boundingBox,
    int maxZoom,
    List<String> layers,
    boolean includesVector,
    String pmtilesFilename,
    Instant createdAt,
    Long sizeBytes) {}
