package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Layer {

  public enum SourceType {
    XYZ,
    WMTS_REST,
    WMTS_KVP,
    LOCAL,
    VECTOR_PMTILES
  }

  private SourceType sourceType = SourceType.XYZ;

  // WMTS-specific (used when sourceType = WMTS_KVP)
  private String wmtsLayerName;
  private String wmtsTileMatrixSet = "EPSG:3857";
  private String wmtsStyle = "default";
  private String wmtsFormat = "image/png";

  private boolean wmtsTime = false;

  private int tileExpirationMinutes = 0; // 0 = never expire

  private int maxZoom = 22;

  private int initZoom = 0;

  private String timeFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'";

  private String attribution;

  private String id;

  private String name;

  private String urlTemplate;

  private Map<String, String> headers = new HashMap<>();

  private List<String> allowedUsers = new ArrayList<>();

  private List<String> allowedGroups = new ArrayList<>();

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

  /** Returns the identifier used in URLs and as map key. Falls back to name for backward compat. */
  @JsonIgnore
  public String getEffectiveId() {
    return (id != null && !id.isBlank()) ? id : name;
  }

  public String getUrlTemplate() {
    return urlTemplate;
  }

  public void setUrlTemplate(String urlTemplate) {
    this.urlTemplate = urlTemplate;
  }

  public SourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(SourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getWmtsFormat() {
    return wmtsFormat;
  }

  public void setWmtsFormat(String wmtsFormat) {
    this.wmtsFormat = wmtsFormat;
  }

  public String getWmtsStyle() {
    return wmtsStyle;
  }

  public void setWmtsStyle(String wmtsStyle) {
    this.wmtsStyle = wmtsStyle;
  }

  public String getWmtsTileMatrixSet() {
    return wmtsTileMatrixSet;
  }

  public void setWmtsTileMatrixSet(String wmtsTileMatrixSet) {
    this.wmtsTileMatrixSet = wmtsTileMatrixSet;
  }

  public String getWmtsLayerName() {
    return wmtsLayerName;
  }

  public void setWmtsLayerName(String wmtsLayerName) {
    this.wmtsLayerName = wmtsLayerName;
  }

  public int getTileExpirationMinutes() {
    return tileExpirationMinutes;
  }

  public void setTileExpirationMinutes(int tileExpirationMinutes) {
    this.tileExpirationMinutes = tileExpirationMinutes;
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

  public String getTimeFormat() {
    return timeFormat;
  }

  public void setTimeFormat(String timeFormat) {
    this.timeFormat = timeFormat;
  }

  public boolean isWmtsTime() {
    return wmtsTime;
  }

  public void setWmtsTime(boolean wmtsTime) {
    this.wmtsTime = wmtsTime;
  }

  public boolean doesUrlHaveTime() {
    return (urlTemplate != null && urlTemplate.contains("{time}")) || wmtsTime;
  }

  public String getAttribution() {
    return attribution;
  }

  public void setAttribution(String attribution) {
    this.attribution = attribution;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
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

  @JsonIgnore
  public boolean isPublic() {
    return allowedUsers.isEmpty() && allowedGroups.isEmpty();
  }

  public enum RequestStrategy {
    PROCEED,
    RETRY,
    BLOCK
  }

  @Override
  public String toString() {
    return getEffectiveId();
  }
}
