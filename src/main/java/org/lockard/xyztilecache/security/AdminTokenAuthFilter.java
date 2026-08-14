package org.lockard.xyztilecache.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminTokenAuthFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdminTokenAuthFilter.class);

  private static final String BEARER = "Bearer ";

  private final String expectedToken;
  private final String adminAuthority;
  private final AuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();

  public AdminTokenAuthFilter(String expectedToken, String adminRole) {
    // Trim the configured token: a stray space or newline picked up from YAML quoting, a docker
    // -e flag or $(cat token.txt) is impossible for any client to reproduce, because HTTP strips
    // trailing whitespace from header values in transit. Untrimmed, such a token can never match.
    this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    this.adminAuthority = "ROLE_" + adminRole.toUpperCase();
  }

  @Override
  public void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    // RFC 7235 defines the auth scheme as case-insensitive, and Spring's own
    // BearerTokenAuthenticationFilter matches it that way, so "bearer <token>" must work here too.
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      // No bearer credential presented at all; stay anonymous so public GETs still work.
      chain.doFilter(request, response);
      return;
    }

    String presented = header.substring(BEARER.length()).trim();
    if (!matches(presented)) {
      // Match jwt mode: reject an invalid credential outright rather than silently degrading to
      // anonymous, which made a mistyped token indistinguishable from sending no token at all.
      // Logged at debug: this fires once per rejected request, so anything spraying tokens (or one
      // misconfigured polling client) would otherwise write an unbounded stream of warnings.
      LOGGER.debug(
          "Rejected bearer token for {} {}: does not match the configured xyz.auth.adminToken.",
          request.getMethod(),
          request.getRequestURI());
      entryPoint.commence(
          request, response, new InvalidBearerTokenException("Invalid admin token"));
      return;
    }

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "admin", null, List.of(new SimpleGrantedAuthority(adminAuthority)));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    chain.doFilter(request, response);
  }

  private boolean matches(String presented) {
    if (expectedToken.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
  }
}
