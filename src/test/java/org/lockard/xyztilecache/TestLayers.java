package org.lockard.xyztilecache;

import java.util.List;
import java.util.Map;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LocalLayer;
import org.lockard.xyztilecache.model.VectorPmtilesLayer;
import org.lockard.xyztilecache.model.WmtsKvpLayer;
import org.lockard.xyztilecache.model.WmtsRestLayer;
import org.lockard.xyztilecache.model.XyzLayer;

/** Test-only helpers for constructing {@link Layer} records compactly. */
public final class TestLayers {
  private TestLayers() {}

  public static XyzLayer xyz(String id, String urlTemplate) {
    return new XyzLayer(id, id, urlTemplate, null, 22, 0, 0, List.of(), List.of(), Map.of(), null);
  }

  public static WmtsRestLayer wmtsRest(String id, String urlTemplate) {
    return new WmtsRestLayer(
        id, id, urlTemplate, null, 22, 0, 0, List.of(), List.of(), Map.of(), null);
  }

  public static WmtsKvpLayer wmtsKvp(String id, String urlTemplate) {
    return new WmtsKvpLayer(
        id,
        id,
        urlTemplate,
        null,
        22,
        0,
        0,
        List.of(),
        List.of(),
        Map.of(),
        null,
        "EPSG:3857",
        "default",
        "image/png",
        false,
        null);
  }

  public static LocalLayer local(String id) {
    return new LocalLayer(id, id, null, 22, 0, 0, List.of(), List.of());
  }

  public static VectorPmtilesLayer vectorPmtiles(String id, String urlTemplate) {
    return new VectorPmtilesLayer(id, id, urlTemplate, null, 22, 0, 0, List.of(), List.of());
  }

  /**
   * A {@link LayerProperties} pre-populated for the simplest XYZ case used by Spring-bound tests.
   */
  public static LayerProperties xyzProperties(String id, String urlTemplate) {
    LayerProperties p = new LayerProperties();
    p.setId(id);
    p.setName(id);
    p.setUrlTemplate(urlTemplate);
    return p;
  }
}
