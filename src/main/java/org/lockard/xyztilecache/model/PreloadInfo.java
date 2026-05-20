package org.lockard.xyztilecache.model;

import java.time.Instant;
import java.util.List;

public record PreloadInfo(
    String id,
    String name,
    BoundingBox boundingBox,
    int maxZoom,
    List<String> layers,
    String pmtilesFilename,
    Instant createdAt,
    Long sizeBytes,
    List<String> allowedUsers,
    List<String> allowedGroups) {}
