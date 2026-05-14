package org.lockard.xyztilecache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class UiFilterTest {

  private UiFilter filter(boolean uiEnabled) {
    XyzConfiguration config = new XyzConfiguration();
    config.setUiEnabled(uiEnabled);
    return new UiFilter(config);
  }

  @Test
  void filter_uiEnabled_uiPath_passesThrough() throws Exception {
    UiFilter f = filter(true);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/");

    f.doFilterInternal(req, res, chain);

    verify(chain).doFilter(req, res);
    verify(res, never()).sendError(anyInt());
  }

  @Test
  void filter_uiDisabled_nonUiPath_passesThrough() throws Exception {
    UiFilter f = filter(false);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/layers");

    f.doFilterInternal(req, res, chain);

    verify(chain).doFilter(req, res);
    verify(res, never()).sendError(anyInt());
  }

  @Test
  void filter_uiDisabled_rootPath_returns404() throws Exception {
    UiFilter f = filter(false);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/");

    f.doFilterInternal(req, res, chain);

    verify(res).sendError(HttpServletResponse.SC_NOT_FOUND);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void filter_uiDisabled_indexHtml_returns404() throws Exception {
    UiFilter f = filter(false);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/index.html");

    f.doFilterInternal(req, res, chain);

    verify(res).sendError(HttpServletResponse.SC_NOT_FOUND);
  }
}
