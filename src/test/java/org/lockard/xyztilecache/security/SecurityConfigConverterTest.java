package org.lockard.xyztilecache.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigConverterTest {

  private final SecurityConfig.RealmRolesConverter converter =
      new SecurityConfig.RealmRolesConverter();

  private static Jwt.Builder jwtBuilder() {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .subject("user");
  }

  @Test
  void convert_realmAccessWithRoles_addsRolePrefixedAuthorities() {
    Jwt jwt =
        jwtBuilder().claim("realm_access", Map.of("roles", List.of("admin", "viewer"))).build();
    Collection<GrantedAuthority> authorities = converter.convert(jwt);
    assertThat(authorities)
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ADMIN", "ROLE_VIEWER");
  }

  @Test
  void convert_noRealmAccess_returnsEmptyOrScopeOnly() {
    Jwt jwt = jwtBuilder().build();
    Collection<GrantedAuthority> authorities = converter.convert(jwt);
    assertThat(authorities).allSatisfy(a -> assertThat(a.getAuthority()).doesNotStartWith("ROLE_"));
  }

  @Test
  void convert_realmAccessWithoutRolesList_returnsEmpty() {
    Jwt jwt = jwtBuilder().claim("realm_access", Map.of("other", "value")).build();
    Collection<GrantedAuthority> authorities = converter.convert(jwt);
    assertThat(authorities).allSatisfy(a -> assertThat(a.getAuthority()).doesNotStartWith("ROLE_"));
  }

  @Test
  void convert_realmAccessWithNullRoleEntry_skipsNull() {
    java.util.List<String> roles = new java.util.ArrayList<>();
    roles.add("admin");
    roles.add(null);
    Jwt jwt =
        jwtBuilder()
            .claim("realm_access", Map.of("roles", java.util.Collections.unmodifiableList(roles)))
            .build();
    Collection<GrantedAuthority> authorities = converter.convert(jwt);
    assertThat(authorities)
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ADMIN")
        .doesNotContain("ROLE_NULL");
  }
}
