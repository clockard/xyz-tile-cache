package org.lockard.xyztilecache;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
class ConfigController {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigController.class);

  private final XyzConfiguration configuration;

  ConfigController(XyzConfiguration configuration) {
    this.configuration = configuration;
  }

  @GetMapping("/offline")
  Map<String, Boolean> getOffline() {
    return Map.of("offline", configuration.isOffline());
  }

  @PutMapping("/offline")
  ResponseEntity<Map<String, Boolean>> setOffline(@RequestBody Map<String, Boolean> body) {
    boolean offline = Boolean.TRUE.equals(body.get("offline"));
    configuration.setOffline(offline);
    LOGGER.info("Offline mode set to {}", offline);
    return ResponseEntity.ok(Map.of("offline", configuration.isOffline()));
  }
}
