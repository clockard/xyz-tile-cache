package org.lockard.xyztilecache.controller;

import java.util.Map;
import org.lockard.xyztilecache.config.XyzConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthConfigController {

  private final XyzConfiguration configuration;
  private final String issuerUri;

  AuthConfigController(
      XyzConfiguration configuration,
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
    this.configuration = configuration;
    this.issuerUri = issuerUri;
  }

  @GetMapping("/config")
  Map<String, String> config() {
    XyzConfiguration.Auth auth = configuration.getAuth();
    if (auth.getMode() == XyzConfiguration.Auth.Mode.TOKEN) {
      return Map.of("mode", "token");
    }
    return Map.of("mode", "jwt", "issuerUri", issuerUri, "clientId", auth.getClientId());
  }
}
