package org.lockard.xyztilecache;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminKeyInterceptor implements HandlerInterceptor {

  static final String HEADER = "X-Admin-Key";

  private final XyzConfiguration configuration;

  public AdminKeyInterceptor(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (HttpMethod.GET.matches(request.getMethod())) {
      return true;
    }
    String adminKey = configuration.getAdminKey();
    if (adminKey == null || adminKey.isBlank()) {
      response.sendError(
          HttpServletResponse.SC_FORBIDDEN, "Admin key not configured on this server.");
      return false;
    }
    if (!adminKey.equals(request.getHeader(HEADER))) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing " + HEADER + ".");
      return false;
    }
    return true;
  }
}
