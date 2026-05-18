package org.lockard.xyztilecache.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminTokenAuthFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void filter_noAuthHeader_passesThrough() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("secret", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn(null);

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_headerNotBearer_passesThrough() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("secret", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_correctToken_setsAdminAuthentication() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("mytoken", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer mytoken");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_wrongToken_doesNotSetAuth() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("secret", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer wrongtoken");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_blankExpectedToken_doesNotSetAuth() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer anything");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(req, res);
  }
}
