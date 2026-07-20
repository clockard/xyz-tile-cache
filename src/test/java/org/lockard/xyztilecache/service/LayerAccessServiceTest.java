package org.lockard.xyztilecache.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lockard.xyztilecache.config.LayerProperties;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.model.Preload;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class LayerAccessServiceTest {

  private final LayerAccessService service = new LayerAccessService(new XyzConfiguration());

  private static Layer publicLayer() {
    LayerProperties l = new LayerProperties();
    l.setName("public");
    return l.toLayer();
  }

  private static Layer userRestricted(String... users) {
    LayerProperties l = new LayerProperties();
    l.setName("user-restricted");
    l.setAllowedUsers(List.of(users));
    return l.toLayer();
  }

  private static Layer groupRestricted(String... groups) {
    LayerProperties l = new LayerProperties();
    l.setName("group-restricted");
    l.setAllowedGroups(List.of(groups));
    return l.toLayer();
  }

  private static Authentication userAuth(String username, List<String> groups, String... roles) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .subject(username)
            .claim("preferred_username", username);
    if (groups != null) {
      builder.claim("groups", groups);
    }
    Jwt jwt = builder.build();
    var authorities =
        java.util.Arrays.stream(roles)
            .map(SimpleGrantedAuthority::new)
            .map(a -> (org.springframework.security.core.GrantedAuthority) a)
            .toList();
    return new JwtAuthenticationToken(jwt, authorities, username);
  }

  @Test
  void publicLayer_anonymous_canRead() {
    assertThat(service.canRead(publicLayer(), null)).isTrue();
  }

  @Test
  void publicLayer_anonymousAuth_canRead() {
    Authentication anon =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
    assertThat(service.canRead(publicLayer(), anon)).isTrue();
  }

  @Test
  void restrictedLayer_anonymous_cannotRead() {
    assertThat(service.canRead(userRestricted("alice"), null)).isFalse();
  }

  @Test
  void restrictedLayer_admin_canRead() {
    Authentication admin = userAuth("alice", null, "ROLE_ADMIN");
    assertThat(service.canRead(userRestricted("nobody"), admin)).isTrue();
  }

  @Test
  void restrictedLayer_userInAllowedUsers_canRead() {
    Authentication bob = userAuth("bob", null);
    assertThat(service.canRead(userRestricted("bob", "carol"), bob)).isTrue();
  }

  @Test
  void restrictedLayer_userNotInAllowedUsers_cannotRead() {
    Authentication dan = userAuth("dan", List.of("team-misc"));
    assertThat(service.canRead(userRestricted("bob"), dan)).isFalse();
  }

  @Test
  void restrictedLayer_userInAllowedGroup_canRead() {
    Authentication bob = userAuth("bob", List.of("team-foresters"));
    assertThat(service.canRead(groupRestricted("team-foresters"), bob)).isTrue();
  }

  @Test
  void restrictedLayer_userNotInAllowedGroup_cannotRead() {
    Authentication dan = userAuth("dan", List.of("team-misc"));
    assertThat(service.canRead(groupRestricted("team-foresters"), dan)).isFalse();
  }

  @Test
  void restrictedLayer_userWithoutGroupsClaim_cannotReadGroupOnly() {
    Authentication dan = userAuth("dan", null);
    assertThat(service.canRead(groupRestricted("team-foresters"), dan)).isFalse();
  }

  @Test
  void restrictedLayer_nonJwtPrincipal_cannotRead() {
    Authentication weird =
        new UsernamePasswordAuthenticationToken(
            "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
    assertThat(service.canRead(userRestricted("alice"), weird)).isFalse();
  }

  // ── canViewPreload ────────────────────────────────────────────────────────

  private static Preload publicPreload() {
    Preload p = new Preload();
    p.setId("1");
    return p;
  }

  private static Preload restrictedPreload(String... users) {
    Preload p = new Preload();
    p.setId("2");
    p.setAllowedUsers(new ArrayList<>(List.of(users)));
    return p;
  }

  @Test
  void publicPreload_anonymous_canView() {
    assertThat(service.canViewPreload(publicPreload(), null)).isTrue();
  }

  @Test
  void restrictedPreload_anonymous_cannotView() {
    assertThat(service.canViewPreload(restrictedPreload("alice"), null)).isFalse();
  }

  @Test
  void restrictedPreload_admin_canView() {
    Authentication admin = userAuth("alice", null, "ROLE_ADMIN");
    assertThat(service.canViewPreload(restrictedPreload("nobody"), admin)).isTrue();
  }

  @Test
  void restrictedPreload_matchingUser_canView() {
    Authentication bob = userAuth("bob", null);
    assertThat(service.canViewPreload(restrictedPreload("bob"), bob)).isTrue();
  }

  @Test
  void restrictedPreload_nonMatchingUser_cannotView() {
    Authentication dan = userAuth("dan", List.of());
    assertThat(service.canViewPreload(restrictedPreload("alice"), dan)).isFalse();
  }

  @Test
  void restrictedLayer_userInAllowedGroup_alongWithRealmRolesClaim_canRead() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .subject("bob")
            .claim("preferred_username", "bob")
            .claim("groups", List.of("team-foresters"))
            .claim("realm_access", Map.of("roles", List.of("some-role")))
            .build();
    Authentication auth = new JwtAuthenticationToken(jwt, List.of(), "bob");
    assertThat(service.canRead(groupRestricted("team-foresters"), auth)).isTrue();
  }
}
