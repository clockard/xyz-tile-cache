package org.lockard.xyztilecache;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final XyzConfiguration configuration;

  public WebConfig(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new AdminKeyInterceptor(configuration))
        .addPathPatterns("/layers/**", "/vector/preload", "/preloads", "/preloads/**");
  }
}
