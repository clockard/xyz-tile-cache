package org.lockard.xyztilecache.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final XyzConfiguration configuration;

  public SecurityConfig(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.ignoringRequestMatchers(AntPathRequestMatcher.antMatcher("/**")))
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
                        "/auth/config")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/export")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/import")
                    .hasRole(configuration.getAdminRole().toUpperCase())
                    .anyRequest()
                    .hasRole(configuration.getAdminRole().toUpperCase()));

    if (configuration.getAuth().getMode() == XyzConfiguration.Auth.Mode.TOKEN) {
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
