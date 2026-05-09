package org.lockard.xyztilecache;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminTokenAuthFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final String expectedToken;
  private final String adminAuthority;

  public AdminTokenAuthFilter(String expectedToken, String adminRole) {
    this.expectedToken = expectedToken;
    this.adminAuthority = "ROLE_" + adminRole.toUpperCase();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER)) {
      String presented = header.substring(BEARER.length());
      if (matches(presented)) {
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(adminAuthority)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    chain.doFilter(request, response);
  }

  private boolean matches(String presented) {
    if (expectedToken == null || expectedToken.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
  }
}
