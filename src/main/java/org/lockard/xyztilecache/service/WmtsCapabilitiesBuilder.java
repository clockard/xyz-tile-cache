package org.lockard.xyztilecache.service;

import java.util.Collection;
import java.util.Locale;
import org.lockard.xyztilecache.model.Layer;
import org.springframework.stereotype.Service;

/**
 * Builds a minimal WMTS 1.0.0 Capabilities document.
 *
 * <p>Declares each tile-cache layer against the standard {@code GoogleMapsCompatible} TileMatrixSet
 * (EPSG:3857, 256×256, zoom 0–22). The {@code ResourceURL template} points back at the tile-cache's
 * own {@code /tilesZXY} endpoint.
 */
@Service
public class WmtsCapabilitiesBuilder {

  /** EPSG:3857 world half-extent in meters. */
  private static final double WEB_MERCATOR_HALF = 20037508.3427892;

  /** Scale denominator at zoom 0 for GoogleMapsCompatible (256 px tiles, 0.28 mm pixel). */
  private static final double SCALE_DENOM_Z0 = 559082264.0287178;

  /** Max zoom level emitted in the TileMatrixSet. */
  private static final int MATRIX_MAX_ZOOM = 22;

  public String build(Collection<Layer> layers, String baseUrl) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<Capabilities xmlns=\"http://www.opengis.net/wmts/1.0\"")
        .append(" xmlns:ows=\"http://www.opengis.net/ows/1.1\"")
        .append(" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
        .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        .append(" version=\"1.0.0\">\n");

    sb.append("  <ows:ServiceIdentification>\n")
        .append("    <ows:Title>xyz-tile-cache</ows:Title>\n")
        .append("    <ows:ServiceType>OGC WMTS</ows:ServiceType>\n")
        .append("    <ows:ServiceTypeVersion>1.0.0</ows:ServiceTypeVersion>\n")
        .append("  </ows:ServiceIdentification>\n");

    sb.append("  <ows:OperationsMetadata>\n")
        .append("    <ows:Operation name=\"GetCapabilities\">\n")
        .append("      <ows:DCP><ows:HTTP><ows:Get xlink:href=\"")
        .append(xmlAttr(baseUrl))
        .append("/wmts/1.0.0/WMTSCapabilities.xml\">\n")
        .append(
            "        <ows:Constraint name=\"GetEncoding\"><ows:AllowedValues>"
                + "<ows:Value>RESTful</ows:Value></ows:AllowedValues></ows:Constraint>\n")
        .append("      </ows:Get></ows:HTTP></ows:DCP>\n")
        .append("    </ows:Operation>\n")
        .append("  </ows:OperationsMetadata>\n");

    sb.append("  <Contents>\n");
    for (Layer layer : layers) {
      appendLayer(sb, layer, baseUrl);
    }
    appendTileMatrixSet(sb);
    sb.append("  </Contents>\n");

    sb.append("  <ServiceMetadataURL xlink:href=\"")
        .append(xmlAttr(baseUrl))
        .append("/wmts/1.0.0/WMTSCapabilities.xml\"/>\n");

    sb.append("</Capabilities>\n");
    return sb.toString();
  }

  private void appendLayer(StringBuilder sb, Layer layer, String baseUrl) {
    String id = layer.effectiveId();
    String title = layer.name() != null ? layer.name() : id;
    String mime = mimeFor(layer);
    String ext =
        layer.sourceType() == Layer.SourceType.VECTOR_PMTILES ? "pbf" : layer.tileFileExtension();
    int maxZoom = Math.min(layer.maxZoom(), MATRIX_MAX_ZOOM);

    sb.append("    <Layer>\n")
        .append("      <ows:Title>")
        .append(xmlText(title))
        .append("</ows:Title>\n");
    if (layer.attribution() != null && !layer.attribution().isBlank()) {
      sb.append("      <ows:Abstract>")
          .append(xmlText(layer.attribution()))
          .append("</ows:Abstract>\n");
    }
    sb.append("      <ows:Identifier>")
        .append(xmlText(id))
        .append("</ows:Identifier>\n")
        .append("      <ows:WGS84BoundingBox>\n")
        .append("        <ows:LowerCorner>-180 -85.05112878</ows:LowerCorner>\n")
        .append("        <ows:UpperCorner>180 85.05112878</ows:UpperCorner>\n")
        .append("      </ows:WGS84BoundingBox>\n")
        .append(
            "      <Style isDefault=\"true\"><ows:Identifier>default</ows:Identifier></Style>\n")
        .append("      <Format>")
        .append(mime)
        .append("</Format>\n")
        .append("      <TileMatrixSetLink>\n")
        .append("        <TileMatrixSet>GoogleMapsCompatible</TileMatrixSet>\n");
    if (maxZoom < MATRIX_MAX_ZOOM) {
      sb.append("        <TileMatrixSetLimits>\n");
      for (int z = 0; z <= maxZoom; z++) {
        long side = 1L << z;
        sb.append("          <TileMatrixLimits>\n")
            .append("            <TileMatrix>")
            .append(z)
            .append("</TileMatrix>\n")
            .append("            <MinTileRow>0</MinTileRow>\n")
            .append("            <MaxTileRow>")
            .append(side - 1)
            .append("</MaxTileRow>\n")
            .append("            <MinTileCol>0</MinTileCol>\n")
            .append("            <MaxTileCol>")
            .append(side - 1)
            .append("</MaxTileCol>\n")
            .append("          </TileMatrixLimits>\n");
      }
      sb.append("        </TileMatrixSetLimits>\n");
    }
    sb.append("      </TileMatrixSetLink>\n")
        .append("      <ResourceURL format=\"")
        .append(mime)
        .append("\"")
        .append(" resourceType=\"tile\"")
        .append(" template=\"")
        .append(xmlAttr(baseUrl))
        .append("/tilesZXY/")
        .append(xmlAttr(id))
        .append("/{TileMatrix}/{TileCol}/{TileRow}.")
        .append(ext)
        .append("\"/>\n")
        .append("    </Layer>\n");
  }

  private void appendTileMatrixSet(StringBuilder sb) {
    sb.append("    <TileMatrixSet>\n")
        .append("      <ows:Identifier>GoogleMapsCompatible</ows:Identifier>\n")
        .append("      <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>\n")
        .append(
            "      <WellKnownScaleSet>urn:ogc:def:wkss:OGC:1.0:GoogleMapsCompatible</WellKnownScaleSet>\n");
    for (int z = 0; z <= MATRIX_MAX_ZOOM; z++) {
      long side = 1L << z;
      double scale = SCALE_DENOM_Z0 / Math.pow(2, z);
      sb.append("      <TileMatrix>\n")
          .append("        <ows:Identifier>")
          .append(z)
          .append("</ows:Identifier>\n")
          .append("        <ScaleDenominator>")
          .append(String.format(Locale.ROOT, "%.10f", scale))
          .append("</ScaleDenominator>\n")
          .append("        <TopLeftCorner>")
          .append(String.format(Locale.ROOT, "%.7f", -WEB_MERCATOR_HALF))
          .append(' ')
          .append(String.format(Locale.ROOT, "%.7f", WEB_MERCATOR_HALF))
          .append("</TopLeftCorner>\n")
          .append("        <TileWidth>256</TileWidth>\n")
          .append("        <TileHeight>256</TileHeight>\n")
          .append("        <MatrixWidth>")
          .append(side)
          .append("</MatrixWidth>\n")
          .append("        <MatrixHeight>")
          .append(side)
          .append("</MatrixHeight>\n")
          .append("      </TileMatrix>\n");
    }
    sb.append("    </TileMatrixSet>\n");
  }

  private static String mimeFor(Layer layer) {
    if (layer.sourceType() == Layer.SourceType.VECTOR_PMTILES) {
      return "application/vnd.mapbox-vector-tile";
    }
    return switch (layer.tileFileExtension()) {
      case "jpg" -> "image/jpeg";
      case "webp" -> "image/webp";
      case "gif" -> "image/gif";
      default -> "image/png";
    };
  }

  private static String xmlText(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String xmlAttr(String s) {
    return xmlText(s).replace("\"", "&quot;");
  }
}
