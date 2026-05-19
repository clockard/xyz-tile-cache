package org.lockard.xyztilecache.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PreloadCreateRequest {

  private String name;
  private BoundingBox boundingBox;
  private int maxZoom = 15;
  private Set<String> layers = new HashSet<>();
  private boolean includeVector;
  private String vectorLayerId;
  private List<String> allowedUsers = new ArrayList<>();
  private List<String> allowedGroups = new ArrayList<>();

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

  public Set<String> getLayers() {
    return layers;
  }

  public void setLayers(Set<String> layers) {
    this.layers = layers == null ? new HashSet<>() : layers;
  }

  public boolean isIncludeVector() {
    return includeVector;
  }

  public void setIncludeVector(boolean includeVector) {
    this.includeVector = includeVector;
  }

  public String getVectorLayerId() {
    return vectorLayerId;
  }

  public void setVectorLayerId(String vectorLayerId) {
    this.vectorLayerId = vectorLayerId;
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
}
