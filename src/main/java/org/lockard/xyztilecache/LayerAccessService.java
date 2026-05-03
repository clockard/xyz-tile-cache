package org.lockard.xyztilecache;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class LayerAccessService {

  static final String USERNAME_CLAIM = "preferred_username";
  static final String GROUPS_CLAIM = "groups";

  private final String adminAuthority;

  public LayerAccessService(XyzConfiguration configuration) {
    this.adminAuthority = "ROLE_" + configuration.getAdminRole().toUpperCase();
  }

  public boolean canRead(Layer layer, Authentication auth) {
    return checkAccess(layer.isPublic(), layer.getAllowedUsers(), layer.getAllowedGroups(), auth);
  }

  public boolean canViewPreload(Preload preload, Authentication auth) {
    return checkAccess(
        preload.isPublic(), preload.getAllowedUsers(), preload.getAllowedGroups(), auth);
  }

  private boolean checkAccess(
      boolean isPublic,
      List<String> allowedUsers,
      List<String> allowedGroups,
      Authentication auth) {
    if (isPublic) {
      return true;
    }
    if (!isAuthenticated(auth)) {
      return false;
    }
    if (hasAdminRole(auth)) {
      return true;
    }
    if (!(auth.getPrincipal() instanceof Jwt jwt)) {
      return false;
    }
    String username = jwt.getClaimAsString(USERNAME_CLAIM);
    if (username != null && allowedUsers.contains(username)) {
      return true;
    }
    List<String> groups = jwt.getClaimAsStringList(GROUPS_CLAIM);
    if (groups == null) {
      return false;
    }
    for (String group : groups) {
      if (allowedGroups.contains(group)) {
        return true;
      }
    }
    return false;
  }

  private boolean isAuthenticated(Authentication auth) {
    return auth != null
        && auth.isAuthenticated()
        && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
  }

  private boolean hasAdminRole(Authentication auth) {
    Collection<? extends GrantedAuthority> authorities =
        auth.getAuthorities() == null ? Collections.emptyList() : auth.getAuthorities();
    for (GrantedAuthority authority : authorities) {
      if (adminAuthority.equals(authority.getAuthority())) {
        return true;
      }
    }
    return false;
  }
}
