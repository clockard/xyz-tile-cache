package org.lockard.xyztilecache.controller;

import java.util.List;
import org.lockard.xyztilecache.model.Layer;
import org.lockard.xyztilecache.service.LayerAccessService;
import org.lockard.xyztilecache.service.WmtsCapabilitiesBuilder;
import org.lockard.xyztilecache.store.LayerStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
class WmtsController {

  private static final MediaType APPLICATION_XML = MediaType.parseMediaType("application/xml");

  private final LayerStore layerStore;
  private final LayerAccessService layerAccessService;
  private final WmtsCapabilitiesBuilder builder;

  WmtsController(
      LayerStore layerStore,
      LayerAccessService layerAccessService,
      WmtsCapabilitiesBuilder builder) {
    this.layerStore = layerStore;
    this.layerAccessService = layerAccessService;
    this.builder = builder;
  }

  /** RESTful WMTS Capabilities path. */
  @GetMapping(value = "/wmts/1.0.0/WMTSCapabilities.xml")
  ResponseEntity<String> capabilitiesRest() {
    return capabilitiesResponse();
  }

  /**
   * KVP WMTS Capabilities entrypoint. Accepts {@code /wmts?service=WMTS&request=GetCapabilities}.
   */
  @GetMapping(value = "/wmts")
  ResponseEntity<String> capabilitiesKvp(
      @RequestParam(name = "service", required = false) String service,
      @RequestParam(name = "request", required = false) String request) {
    if (request != null && !"GetCapabilities".equalsIgnoreCase(request)) {
      return ResponseEntity.badRequest()
          .contentType(APPLICATION_XML)
          .body(
              serviceException(
                  "OperationNotSupported", "request", "Only GetCapabilities is implemented"));
    }
    if (service != null && !"WMTS".equalsIgnoreCase(service)) {
      return ResponseEntity.badRequest()
          .contentType(APPLICATION_XML)
          .body(serviceException("InvalidParameterValue", "service", "service must be WMTS"));
    }
    return capabilitiesResponse();
  }

  private ResponseEntity<String> capabilitiesResponse() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    List<Layer> visible =
        layerStore.getLayers().values().stream()
            .filter(l -> layerAccessService.canRead(l, auth))
            .toList();
    String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    String xml = builder.build(visible, baseUrl);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(APPLICATION_XML);
    headers.add("Access-Control-Allow-Origin", "*");
    return new ResponseEntity<>(xml, headers, HttpStatus.OK);
  }

  private static String serviceException(String code, String locator, String text) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<ExceptionReport xmlns=\"http://www.opengis.net/ows/1.1\" version=\"1.1.0\">\n"
        + "  <Exception exceptionCode=\""
        + code
        + "\" locator=\""
        + locator
        + "\">\n"
        + "    <ExceptionText>"
        + text
        + "</ExceptionText>\n"
        + "  </Exception>\n"
        + "</ExceptionReport>\n";
  }
}
