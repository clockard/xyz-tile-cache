package org.lockard.xyztilecache.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);

  private final XyzConfiguration configuration;

  public SecurityConfig(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.ignoringRequestMatchers(AntPathRequestMatcher.antMatcher("/**")))
        // Spring Security's CorsFilter runs ahead of the authorization filters and answers
        // preflights itself. Without it, the OPTIONS preflight a browser sends before any
        // DELETE/PUT (or JSON POST) falls through to anyRequest().hasRole(...) and is rejected
        // 401 — preflights never carry an Authorization header — so the real request is never sent.
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/style.css",
                        "/favicon.ico",
                        "/static/**",
                        "/auth/config",
                        "/actuator/health/**",
                        "/actuator/info")
                    .permitAll()
                    // Metrics carry per-layer identifiers/volumes that would leak private layer
                    // names; gate prometheus (and any other actuator endpoint) behind admin.
                    .requestMatchers("/actuator/**")
                    .hasRole(configuration.getAdminRole().toUpperCase())
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/export")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/layers/geotiff")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/import")
                    .hasRole(configuration.getAdminRole().toUpperCase())
                    .anyRequest()
                    .hasRole(configuration.getAdminRole().toUpperCase()));

    if (configuration.getAuth().getMode() == XyzConfiguration.Auth.Mode.TOKEN) {
      String adminToken = configuration.getAuth().getAdminToken();
      if (adminToken == null || adminToken.isBlank()) {
        LOGGER.warn(
            "xyz.auth.mode=token but xyz.auth.adminToken is blank: no token can authenticate, so"
                + " every write request will be rejected 401. Set xyz.auth.adminToken (env"
                + " XYZ_AUTH_ADMIN_TOKEN).");
      }
      http.addFilterBefore(
              new AdminTokenAuthFilter(
                  configuration.getAuth().getAdminToken(), configuration.getAdminRole()),
              UsernamePasswordAuthenticationFilter.class)
          .exceptionHandling(
              ex -> ex.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()));
    } else {
      http.oauth2ResourceServer(
          oauth2 ->
              oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    }
    return http.build();
  }

  /**
   * Permissive CORS matching the {@code Access-Control-Allow-Origin: *} the read endpoints used to
   * set by hand. Credentials stay disabled, which is what allows the {@code *} wildcard origin;
   * tokens travel in the Authorization header, not cookies, so nothing depends on them.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("*"));
    config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RealmRolesConverter());
    return converter;
  }

  public static final class RealmRolesConverter
      implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final JwtGrantedAuthoritiesConverter scopeConverter =
        new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      Collection<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));
      Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
      if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
        for (Object role : roles) {
          if (role != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()));
          }
        }
      }
      return authorities;
    }
  }
}
