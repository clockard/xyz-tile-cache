package org.lockard.xyztilecache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminKeyInterceptorTest {

  private static final String VALID_KEY = "secret";

  private XyzConfiguration configuration;
  private AdminKeyInterceptor interceptor;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    configuration = new XyzConfiguration();
    configuration.setLayers(List.of());
    interceptor = new AdminKeyInterceptor(configuration);
    response = new MockHttpServletResponse();
  }

  @Test
  void getRequestPassesWithoutKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/layers");
    assertThat(interceptor.preHandle(request, response, null)).isTrue();
  }

  @Test
  void postBlockedWhenAdminKeyNotConfigured() throws Exception {
    configuration.setAdminKey("");
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/layers");
    request.addHeader(AdminKeyInterceptor.HEADER, "anything");
    assertThat(interceptor.preHandle(request, response, null)).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void postBlockedWhenHeaderMissing() throws Exception {
    configuration.setAdminKey(VALID_KEY);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/layers");
    assertThat(interceptor.preHandle(request, response, null)).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void postBlockedWhenHeaderWrong() throws Exception {
    configuration.setAdminKey(VALID_KEY);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/layers");
    request.addHeader(AdminKeyInterceptor.HEADER, "wrong");
    assertThat(interceptor.preHandle(request, response, null)).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void postAllowedWithCorrectKey() throws Exception {
    configuration.setAdminKey(VALID_KEY);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/layers");
    request.addHeader(AdminKeyInterceptor.HEADER, VALID_KEY);
    assertThat(interceptor.preHandle(request, response, null)).isTrue();
  }

  @Test
  void deleteAllowedWithCorrectKey() throws Exception {
    configuration.setAdminKey(VALID_KEY);
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/layers/osm");
    request.addHeader(AdminKeyInterceptor.HEADER, VALID_KEY);
    assertThat(interceptor.preHandle(request, response, null)).isTrue();
  }
}
