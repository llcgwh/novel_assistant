package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AiLocalAccessFilterTest {
  AiLocalAccessFilter filter = new AiLocalAccessFilter("http://localhost:3000,http://127.0.0.1:3000");
  MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServerName("localhost"); request.setServerPort(8080); request.setRemoteAddr("127.0.0.1");
    request.addHeader("Origin", "http://localhost:3000");
    return request;
  }
  String session() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request("GET", "/api/ai/session"), response, (a,b) -> fail("Session endpoint belongs to the access filter"));
    assertEquals(200, response.getStatus());
    return new ObjectMapper().readTree(response.getContentAsString()).path("token").asText();
  }
  @Test void arbitraryWebsitesRemotePeersAndReboundHostsCannotObtainSessions() throws Exception {
    for (int i = 0; i < 4; i++) {
      var request = request("GET", "/api/ai/session");
      if (i == 0) { request.removeHeader("Origin"); request.addHeader("Origin", "https://evil.example"); }
      if (i == 1) request.setRemoteAddr("192.168.1.10");
      if (i == 2) request.setServerName("evil.example");
      if (i == 3) request.removeHeader("Origin");
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (a,b) -> fail("Untrusted request reached controller"));
      assertEquals(403, response.getStatus()); assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
  }
  @Test void tokensAreRequiredBoundToOriginAndNeverPlacedInCookies() throws Exception {
    String token = session();
    var request = request("PUT", "/api/ai/config"); request.addHeader("X-AI-Session", token); request.setContent("{}".getBytes());
    var response = new MockHttpServletResponse(); AtomicBoolean called = new AtomicBoolean();
    filter.doFilter(request, response, (a,b) -> { called.set(true); assertEquals(token, a.getAttribute(AiLocalAccessFilter.SESSION_ATTRIBUTE)); assertEquals("{}", new String(a.getInputStream().readAllBytes())); });
    assertTrue(called.get()); assertEquals("no-store", response.getHeader("Cache-Control")); assertEquals(0, response.getCookies().length);
    var other = request("GET", "/api/ai/config"); other.removeHeader("Origin"); other.addHeader("Origin", "http://127.0.0.1:3000"); other.addHeader("X-AI-Session", token);
    var rejected = new MockHttpServletResponse(); filter.doFilter(other, rejected, (a,b) -> fail()); assertEquals(401, rejected.getStatus());
    var absent = new MockHttpServletResponse(); filter.doFilter(request("POST", "/api/novels/12/ai/suggestions"), absent, (a,b) -> fail()); assertEquals(401, absent.getStatus());
  }
  @Test void encodedAndSemicolonAiPathsStillRequireAuthorizationAndLargeBodiesAreRejected() throws Exception {
    for (String path : new String[]{"/api/%61i/config", "/api/ai;param=x/config", "/api//ai/config", "/api/unused/../ai/config", "/api/novels/3/ai/suggestions"}) {
      var response = new MockHttpServletResponse(); filter.doFilter(request("GET", path), response, (a,b) -> fail(path)); assertEquals(401, response.getStatus());
    }
    var large = request("PUT", "/api/ai/config"); large.addHeader("X-AI-Session", session()); large.setContent(new byte[16385]);
    var response = new MockHttpServletResponse(); filter.doFilter(large, response, (a,b) -> fail()); assertEquals(413, response.getStatus());
  }
  @Test void normalizedServletPathCannotBypassFilterViaDifferentRawUri() throws Exception {
    var request = request("GET", "/api/unused/../ai/config"); request.setServletPath("/api/ai/config");
    var response = new MockHttpServletResponse(); filter.doFilter(request, response, (a,b) -> fail());
    assertEquals(401, response.getStatus());
  }
  @Test void trustedPreflightWorksWithoutTokenAndLegacyRoutesRemainUnaffected() throws Exception {
    var preflight = new MockHttpServletResponse(); filter.doFilter(request("OPTIONS", "/api/ai/config"), preflight, (a,b) -> fail());
    assertEquals(204, preflight.getStatus()); assertEquals("http://localhost:3000", preflight.getHeader("Access-Control-Allow-Origin"));
    var legacy = new MockHttpServletRequest("GET", "/api/novels"); AtomicBoolean called = new AtomicBoolean();
    filter.doFilter(legacy, new MockHttpServletResponse(), (a,b) -> called.set(true)); assertTrue(called.get());
  }
  @Test void sameOriginBrowserFetchThroughLocalProxyUsesAllowlistedRefererDespiteChangedBackendHost() throws Exception {
    var bootstrap = request("GET", "/api/ai/session"); bootstrap.removeHeader("Origin");
    bootstrap.addHeader("Host", "localhost:8080"); bootstrap.addHeader("Referer", "http://localhost:3000/novel/1/settings");
    bootstrap.addHeader("Sec-Fetch-Site", "same-origin");
    var response = new MockHttpServletResponse(); filter.doFilter(bootstrap, response, (a,b) -> fail());
    assertEquals(200, response.getStatus());
    String token = new ObjectMapper().readTree(response.getContentAsString()).path("token").asText();
    var config = request("GET", "/api/ai/config"); config.removeHeader("Origin");
    config.addHeader("Host", "localhost:8080"); config.addHeader("Referer", "http://localhost:3000/novel/1/settings");
    config.addHeader("Sec-Fetch-Site", "same-origin"); config.addHeader("X-AI-Session", token);
    AtomicBoolean called = new AtomicBoolean(); filter.doFilter(config, new MockHttpServletResponse(), (a,b) -> called.set(true)); assertTrue(called.get());
  }
  @Test void proxyFallbackDoesNotTrustCrossSiteMetadataExternalReferrersOrForwardedHeaders() throws Exception {
    for (int i = 0; i < 4; i++) {
      var request = request("GET", "/api/ai/session"); request.removeHeader("Origin");
      request.addHeader("Host", "localhost:8080"); request.addHeader("Referer", i == 0 ? "https://evil.example/app" : "http://localhost:3000/app");
      if (i != 2) request.addHeader("Sec-Fetch-Site", i == 1 ? "cross-site" : "same-origin");
      request.addHeader("Forwarded", "for=127.0.0.1;host=localhost:3000;proto=http"); request.addHeader("X-Forwarded-Host", "localhost:3000");
      if (i == 3) request.setRemoteAddr("192.168.1.99");
      var response = new MockHttpServletResponse(); filter.doFilter(request, response, (a,b) -> fail()); assertEquals(403, response.getStatus());
    }
  }
}
