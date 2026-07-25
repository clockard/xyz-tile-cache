package org.lockard.xyztilecache.pmtiles;

import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Arrays;

/** WireMock transformer that serves byte-range slices from an in-memory file. */
public class TestRangeResponseTransformer implements ResponseTransformerV2 {

  private final byte[] content;

  public TestRangeResponseTransformer(byte[] content) {
    this.content = content;
  }

  @Override
  public String getName() {
    return "range";
  }

  @Override
  public boolean applyGlobally() {
    // Only act on stubs that opt in via .withTransformers("range").
    return false;
  }

  @Override
  public Response transform(Response response, ServeEvent serveEvent) {
    String rangeHeader = serveEvent.getRequest().getHeader("Range");
    if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
      return response;
    }
    String[] parts = rangeHeader.substring(6).split("-");
    int start = Integer.parseInt(parts[0]);
    int end = Integer.parseInt(parts[1]);
    byte[] chunk = Arrays.copyOfRange(content, start, end + 1);
    return Response.Builder.like(response).but().status(206).body(chunk).build();
  }
}
