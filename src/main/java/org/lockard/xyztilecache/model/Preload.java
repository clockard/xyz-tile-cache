package org.lockard.xyztilecache.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Preload {

  private String id;
  private String name;
  private BoundingBox boundingBox;
  private int maxZoom;
  private List<String> layers = new ArrayList<>();
  private Instant createdAt;
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

  public BoundingBox getBoundingBox() {
    return boundingBox;
  }

  public void setBoundingBox(BoundingBox boundingBox) {
    this.boundingBox = boundingBox;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public List<String> getLayers() {
    return layers;
  }

  public void setLayers(List<String> layers) {
    this.layers = layers == null ? new ArrayList<>() : layers;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
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
}
