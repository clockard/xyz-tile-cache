package org.lockard.xyztilecache.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
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
  void filter_wrongToken_rejectsWithoutContinuingChain() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("secret", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer wrongtoken");

    f.doFilterInternal(req, res, chain);

    // An invalid credential must fail loudly instead of silently degrading to anonymous, which
    // made a mistyped token indistinguishable from sending none at all.
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getHeader("WWW-Authenticate")).contains("error=\"invalid_token\"");
    verifyNoInteractions(chain);
  }

  @Test
  void filter_blankExpectedToken_rejects() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer anything");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(res.getStatus()).isEqualTo(401);
    verifyNoInteractions(chain);
  }

  @Test
  void filter_lowercaseBearerScheme_setsAdminAuthentication() throws Exception {
    // RFC 7235 makes the auth scheme case-insensitive, and jwt mode accepts "bearer"; token mode
    // must not diverge.
    AdminTokenAuthFilter f = new AdminTokenAuthFilter("mytoken", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("bearer mytoken");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_configuredTokenWithSurroundingWhitespace_stillMatches() throws Exception {
    // HTTP strips trailing whitespace from header values in transit, so an untrimmed configured
    // token could never be reproduced by any client and would reject every request.
    AdminTokenAuthFilter f = new AdminTokenAuthFilter(" mytoken\n", "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer mytoken");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    verify(chain).doFilter(req, res);
  }

  @Test
  void filter_nullExpectedToken_rejectsWithoutNpe() throws Exception {
    AdminTokenAuthFilter f = new AdminTokenAuthFilter(null, "admin");
    HttpServletRequest req = mock(HttpServletRequest.class);
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("Authorization")).thenReturn("Bearer anything");

    f.doFilterInternal(req, res, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(res.getStatus()).isEqualTo(401);
    verifyNoInteractions(chain);
  }
}
