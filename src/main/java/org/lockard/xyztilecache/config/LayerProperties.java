package org.lockard.xyztilecache.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.LocalLayer;
import org.lockard.xyztilecache.model.VectorPmtilesLayer;
import org.lockard.xyztilecache.model.WmtsKvpLayer;
import org.lockard.xyztilecache.model.WmtsRestLayer;
import org.lockard.xyztilecache.model.XyzLayer;

/**
 * Flat mutable POJO for binding {@code xyz.layers[*]} from {@code application.yml}.
 *
 * <p>Spring Boot's property binder can't dispatch into a sealed Layer hierarchy directly, so we
 * bind YAML into this DTO and convert to the polymorphic {@link Layer} model via {@link #toLayer()}
 * at startup.
 */
public class LayerProperties {

  private Layer.SourceType sourceType = Layer.SourceType.XYZ;
  private String id;
  private String name;
  private String urlTemplate;
  private String attribution;
  private int maxZoom = 22;
  private int initZoom = 0;
  private int tileExpirationMinutes = 0;
  private List<String> allowedUsers = new ArrayList<>();
  private List<String> allowedGroups = new ArrayList<>();
  private Map<String, String> headers = new HashMap<>();
  private String timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";

  // WMTS KVP specifics
  private String wmtsLayerName;
  private String wmtsTileMatrixSet = "EPSG:3857";
  private String wmtsStyle = "default";
  private String wmtsFormat = "image/png";
  private boolean wmtsTime = false;

  public Layer toLayer() {
    return switch (sourceType) {
      case XYZ ->
          new XyzLayer(
              id,
              name,
              urlTemplate,
              attribution,
              maxZoom,
              initZoom,
              tileExpirationMinutes,
              allowedUsers,
              allowedGroups,
              headers,
              timeFormat);
      case WMTS_REST ->
          new WmtsRestLayer(
              id,
              name,
              urlTemplate,
              attribution,
              maxZoom,
              initZoom,
              tileExpirationMinutes,
              allowedUsers,
              allowedGroups,
              headers,
              timeFormat);
      case WMTS_KVP ->
          new WmtsKvpLayer(
              id,
              name,
              urlTemplate,
              attribution,
              maxZoom,
              initZoom,
              tileExpirationMinutes,
              allowedUsers,
              allowedGroups,
              headers,
              wmtsLayerName,
              wmtsTileMatrixSet,
              wmtsStyle,
              wmtsFormat,
              wmtsTime,
              timeFormat);
      case LOCAL ->
          new LocalLayer(
              id,
              name,
              attribution,
              maxZoom,
              initZoom,
              tileExpirationMinutes,
              allowedUsers,
              allowedGroups);
      case VECTOR_PMTILES ->
          new VectorPmtilesLayer(
              id,
              name,
              urlTemplate,
              attribution,
              maxZoom,
              initZoom,
              tileExpirationMinutes,
              allowedUsers,
              allowedGroups);
    };
  }

  public Layer.SourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(Layer.SourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getUrlTemplate() {
    return urlTemplate;
  }

  public void setUrlTemplate(String urlTemplate) {
    this.urlTemplate = urlTemplate;
  }

  public String getAttribution() {
    return attribution;
  }

  public void setAttribution(String attribution) {
    this.attribution = attribution;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public int getInitZoom() {
    return initZoom;
  }

  public void setInitZoom(int initZoom) {
    this.initZoom = initZoom;
  }

  public int getTileExpirationMinutes() {
    return tileExpirationMinutes;
  }

  public void setTileExpirationMinutes(int tileExpirationMinutes) {
    this.tileExpirationMinutes = tileExpirationMinutes;
  }

  public List<String> getAllowedUsers() {
    return allowedUsers;
  }

  public void setAllowedUsers(List<String> allowedUsers) {
    this.allowedUsers = allowedUsers == null ? new ArrayList<>() : allowedUsers;
  }

  public List<String> getAllowedGroups() {
    return allowedGroups;
  }

  public void setAllowedGroups(List<String> allowedGroups) {
    this.allowedGroups = allowedGroups == null ? new ArrayList<>() : allowedGroups;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers == null ? new HashMap<>() : headers;
  }

  public String getTimeFormat() {
    return timeFormat;
  }

  public void setTimeFormat(String timeFormat) {
    this.timeFormat = timeFormat;
  }

  public String getWmtsLayerName() {
    return wmtsLayerName;
  }

  public void setWmtsLayerName(String wmtsLayerName) {
    this.wmtsLayerName = wmtsLayerName;
  }

  public String getWmtsTileMatrixSet() {
    return wmtsTileMatrixSet;
  }

  public void setWmtsTileMatrixSet(String wmtsTileMatrixSet) {
    this.wmtsTileMatrixSet = wmtsTileMatrixSet;
  }

  public String getWmtsStyle() {
    return wmtsStyle;
  }

  public void setWmtsStyle(String wmtsStyle) {
    this.wmtsStyle = wmtsStyle;
  }

  public String getWmtsFormat() {
    return wmtsFormat;
  }

  public void setWmtsFormat(String wmtsFormat) {
    this.wmtsFormat = wmtsFormat;
  }

  public boolean isWmtsTime() {
    return wmtsTime;
  }

  public void setWmtsTime(boolean wmtsTime) {
    this.wmtsTime = wmtsTime;
  }
}
