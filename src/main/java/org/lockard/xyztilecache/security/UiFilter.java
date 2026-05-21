package org.lockard.xyztilecache.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class UiFilter extends OncePerRequestFilter {

  private static final Set<String> UI_PATHS =
      Set.of("/", "/index.html", "/app.js", "/style.css", "/favicon.ico");

  private final XyzConfiguration configuration;

  public UiFilter(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!configuration.isUiEnabled() && UI_PATHS.contains(request.getRequestURI())) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    chain.doFilter(request, response);
  }
}
