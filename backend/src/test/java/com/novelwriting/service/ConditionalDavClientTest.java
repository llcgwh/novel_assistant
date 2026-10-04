package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;

import com.github.sardine.impl.SardineException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Exercise actual wire requests: mocking Sardine conceals its dropped DELETE headers. */
class ConditionalDavClientTest {
  private static final String TOKEN = "opaquelocktoken:12345678-1234-1234-1234-123456789abc";
  private static final String OTHER = "opaquelocktoken:87654321-1234-1234-1234-123456789abc";
  private HttpServer server;
  private ConditionalDavClient client;
  private String directory;
  private final LinkedBlockingQueue<WireRequest> requests = new LinkedBlockingQueue<>();
  private Consumer<HttpExchange> respond;

  record WireRequest(String method, Map<String, List<String>> headers, String body) {
    String header(String name) {
      return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
        .findFirst().map(e -> e.getValue().get(0)).orElse(null);
    }
  }

  @BeforeEach void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(new WireRequest(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      respond.accept(exchange);
    });
    server.start();
    directory = "http://127.0.0.1:" + server.getAddress().getPort() + "/book/";
    client = new ConditionalDavClient("user", "secret");
    respond = exchange -> reply(exchange, 204, null);
  }

  @AfterEach void stop() throws IOException {
    try { if (client != null) client.shutdown(); }
    finally { if (server != null) server.stop(0); }
  }

  @Test void deleteSendsBothConditionsAndDoesNotRetryPreconditionFailure() throws Exception {
    respond = exchange -> reply(exchange, 412, null);
    String condition = "(<" + TOKEN + ">)";
    SardineException failure = assertThrows(SardineException.class,
      () -> client.delete(directory + "old.ink.json", Map.of("If", condition, "If-Match", "\"version-1\"")));
    assertEquals(412, failure.getStatusCode());
    WireRequest request = request();
    assertEquals("DELETE", request.method());
    assertEquals(condition, request.header("If"));
    assertEquals("\"version-1\"", request.header("If-Match"));
    assertTrue(requests.isEmpty());
  }

  @Test void headReadsTheActualStrongHttpValidatorAndIdentitySize() throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().set("ETag", "\"10447186-1791104841-2434\"");
      exchange.getResponseHeaders().set("Content-Length", "2434");
      reply(exchange, 200, null);
    };
    var metadata = client.headMetadata(directory + "snapshot.ink.json");
    assertEquals("\"10447186-1791104841-2434\"", metadata.etag());
    assertEquals(2434, metadata.size());
    WireRequest request = request();
    assertEquals("HEAD", request.method());
    assertEquals("identity", request.header("Accept-Encoding"));
    assertEquals("no-cache", request.header("Cache-Control"));
    assertTrue(request.body().isEmpty());
  }

  @Test void headCompletesBasicAuthenticationWithoutReadingOrWritingContent() throws Exception {
    respond = exchange -> {
      if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"isolated-head-test\"");
        reply(exchange, 401, null);
      } else {
        exchange.getResponseHeaders().set("ETag", "\"actual-version\"");
        exchange.getResponseHeaders().set("Content-Length", "321");
        reply(exchange, 200, null);
      }
    };
    var metadata = client.headMetadata(directory + "snapshot.ink.json");
    assertEquals("\"actual-version\"", metadata.etag());
    assertEquals(321, metadata.size());
    WireRequest first = request(), authenticated = request();
    assertEquals("HEAD", first.method()); assertNull(first.header("Authorization"));
    assertEquals("HEAD", authenticated.method()); assertNotNull(authenticated.header("Authorization"));
    assertTrue(requests.isEmpty());
  }

  static Stream<String> unsafeHeadValidators() {
    return Stream.of(null, "", "*", "\"*\"", "10447186-1791104841-2434", "W/\"weak\"", "\"one\",\"two\"");
  }

  @ParameterizedTest @MethodSource("unsafeHeadValidators")
  void headNeverNormalizesUnsafeOrBareHttpValidatorsIntoStrongEtags(String etag) throws Exception {
    respond = exchange -> {
      if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
      exchange.getResponseHeaders().set("Content-Length", "2434");
      reply(exchange, 200, null);
    };
    assertNull(client.headMetadata(directory + "snapshot.ink.json").etag());
    assertEquals("HEAD", request().method());
  }

  @Test void headRejectsMultipleValidatorHeaders() throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().add("ETag", "\"one\"");
      exchange.getResponseHeaders().add("ETag", "\"two\"");
      exchange.getResponseHeaders().set("Content-Length", "2434");
      reply(exchange, 200, null);
    };
    assertNull(client.headMetadata(directory + "snapshot.ink.json").etag());
    assertEquals("HEAD", request().method());
  }

  @Test void compressedHeadRepresentationDoesNotClaimTheListedFileSize() throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().set("ETag", "\"compressed\"");
      exchange.getResponseHeaders().set("Content-Length", "123");
      exchange.getResponseHeaders().set("Content-Encoding", "gzip");
      reply(exchange, 200, null);
    };
    assertEquals(-1, client.headMetadata(directory + "snapshot.ink.json").size());
    assertEquals("HEAD", request().method());
  }

  @Test void headDoesNotFollowRedirectsOutsideTheInventoriedResource() throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().set("Location", directory + "different.ink.json");
      reply(exchange, 302, null);
    };
    SardineException failure = assertThrows(SardineException.class,
      () -> client.headMetadata(directory + "snapshot.ink.json"));
    assertEquals(302, failure.getStatusCode());
    assertEquals("HEAD", request().method());
    assertTrue(requests.isEmpty());
  }

  @Test void bridgePutPreservesLockAndCreateOnlyConditions() throws Exception {
    respond = exchange -> reply(exchange, 201, null);
    String condition = "(<" + TOKEN + ">)";
    client.put(directory + "parent_head.resolved", new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
      Map.of("If", condition, "If-None-Match", "*", "Content-Type", "application/json"));
    WireRequest request = request();
    assertEquals("PUT", request.method());
    assertEquals(condition, request.header("If"));
    assertEquals("*", request.header("If-None-Match"));
    assertEquals("{}", request.body());
  }

  @Test void authenticationChallengeResendsTheEntirePutBodyWithItsConditions() throws Exception {
    respond = exchange -> {
      if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"isolated-c10-test\"");
        reply(exchange, 401, null);
      } else reply(exchange, 201, null);
    };
    String payload = "{\"content\":\"含中文的完整备份\"}";
    String condition = "(<" + TOKEN + ">)";
    client.put(directory + "snapshot.ink.json", new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)),
      Map.of("If", condition, "If-None-Match", "*"));
    WireRequest challenged = request(), authenticated = request();
    assertNull(challenged.header("Authorization"));
    assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)),
      authenticated.header("Authorization"));
    for (WireRequest request : List.of(challenged, authenticated)) {
      assertEquals("PUT", request.method());
      assertEquals(payload, request.body());
      assertEquals(condition, request.header("If"));
      assertEquals("*", request.header("If-None-Match"));
    }
    assertTrue(requests.isEmpty());
  }

  @Test void collectionLockAndRefreshAreBoundedAndVerifyTheSameToken() throws Exception {
    respond = exchange -> {
      if (exchange.getRequestMethod().equals("LOCK")) {
        exchange.getResponseHeaders().set("Lock-Token", "<" + TOKEN + ">");
        reply(exchange, 200, lockXml());
      } else reply(exchange, 204, null);
    };
    assertEquals(TOKEN, client.lock(directory));
    WireRequest initial = request();
    assertEquals("LOCK", initial.method());
    assertEquals("infinity", initial.header("Depth"));
    assertEquals("Second-120", initial.header("Timeout"));
    assertTrue(initial.body().contains("exclusive"));
    assertTrue(initial.body().contains("write"));
    assertEquals(TOKEN, client.refreshLock(directory, TOKEN, directory));
    WireRequest refresh = request();
    assertEquals("<" + directory + "> (<" + TOKEN + ">)", refresh.header("If"));
    assertEquals("Second-120", refresh.header("Timeout"));
    assertTrue(refresh.body().isEmpty());
    client.unlock(directory, TOKEN);
    WireRequest unlock = request();
    assertEquals("UNLOCK", unlock.method());
    assertEquals("<" + TOKEN + ">", unlock.header("Lock-Token"));
  }

  @Test void capturedWsgiDavResponseWithBareTokenSupportsVerifiedAcquisitionAndRefresh() throws Exception {
    // Captured from an isolated WsgiDAV directory; only its random token is replaced.
    String xml = """
      <?xml version="1.0" encoding="utf-8" ?>
      <ns0:prop xmlns:ns0="DAV:"><ns0:lockdiscovery><ns0:activelock>
      <ns0:locktype><ns0:write /></ns0:locktype><ns0:lockscope><ns0:exclusive /></ns0:lockscope>
      <ns0:depth>infinity</ns0:depth><ns0:timeout>Second-119</ns0:timeout>
      <ns0:locktoken><ns0:href>%s</ns0:href></ns0:locktoken>
      <ns0:lockroot><ns0:href>/book/</ns0:href></ns0:lockroot>
      </ns0:activelock></ns0:lockdiscovery></ns0:prop>
      """.formatted(TOKEN);
    respond = exchange -> {
      if (exchange.getRequestMethod().equals("LOCK")) {
        // WsgiDAV sends the bare header only on acquisition; refresh identifies
        // the same token in lockdiscovery and omits the response header.
        if (exchange.getRequestHeaders().getFirst("If") == null)
          exchange.getResponseHeaders().set("Lock-Token", TOKEN);
        reply(exchange, 200, xml);
      } else reply(exchange, 204, null);
    };
    assertEquals(TOKEN, client.lock(directory));
    assertEquals("LOCK", request().method());
    assertEquals(TOKEN, client.refreshLock(directory, TOKEN, directory));
    assertEquals("<" + directory + "> (<" + TOKEN + ">)", request().header("If"));
    client.unlock(directory, TOKEN);
    assertEquals("<" + TOKEN + ">", request().header("Lock-Token"));
    assertTrue(requests.isEmpty());
  }

  static Stream<String> unsafeLockTokenHeaders() {
    return Stream.of("", "relative-token", "<" + TOKEN, TOKEN + ">",
      "<" + TOKEN + ">, <" + OTHER + ">", TOKEN + " " + OTHER);
  }

  @ParameterizedTest @MethodSource("unsafeLockTokenHeaders")
  void malformedOrAmbiguousBareLockTokensRemainRejected(String header) throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().set("Lock-Token", header);
      reply(exchange, 200, lockXml());
    };
    assertThrows(IOException.class, () -> client.lock(directory));
    assertEquals("LOCK", request().method());
    assertTrue(requests.isEmpty());
  }

  static Stream<String> unsafeResponses() {
    return Stream.of("shared", "shallow", "infinite", "oversizedLease", "expired", "wrongRoot",
      "wrongToken", "noTimeout", "malformed", "externalEntity", "duplicateActiveLock");
  }

  @ParameterizedTest @MethodSource("unsafeResponses")
  void unsafeSuccessfulLockIsRefusedAndReleased(String variant) throws Exception {
    String xml = switch (variant) {
      case "shared" -> lockXml().replace("exclusive", "shared");
      case "shallow" -> lockXml().replace("infinity", "0");
      case "infinite" -> lockXml().replace("Second-120", "Infinite");
      case "oversizedLease" -> lockXml().replace("Second-120", "Second-121");
      case "expired" -> lockXml().replace("Second-120", "Second-0");
      case "wrongRoot" -> lockXml().replace("/book/", "/other/");
      case "wrongToken" -> lockXml().replace(TOKEN, OTHER);
      case "noTimeout" -> lockXml().replace("<d:timeout>Second-120</d:timeout>", "");
      case "malformed" -> "this is not xml";
      case "externalEntity" -> "<!DOCTYPE prop [<!ENTITY ext SYSTEM 'file:///unreadable-c10-test'>]>" + lockXml();
      case "duplicateActiveLock" -> lockXml().replace("</d:lockdiscovery>", "<d:activelock/></d:lockdiscovery>");
      default -> throw new AssertionError(variant);
    };
    respond = exchange -> {
      if (exchange.getRequestMethod().equals("LOCK")) {
        exchange.getResponseHeaders().set("Lock-Token", "<" + TOKEN + ">");
        reply(exchange, 200, xml);
      } else reply(exchange, 204, null);
    };
    IOException failure = assertThrows(IOException.class, () -> client.lock(directory));
    assertFalse(failure.getMessage().contains(TOKEN));
    assertEquals("LOCK", request().method());
    WireRequest unlock = request();
    assertEquals("UNLOCK", unlock.method());
    assertEquals("<" + TOKEN + ">", unlock.header("Lock-Token"));
    assertTrue(requests.isEmpty());
  }

  @Test void missingAcquisitionTokenDoesNotUnlockAnUnidentifiedLock() throws Exception {
    respond = exchange -> reply(exchange, 200, lockXml());
    assertThrows(IOException.class, () -> client.lock(directory));
    assertEquals("LOCK", request().method());
    assertTrue(requests.isEmpty());
  }

  @Test void refreshCannotSubstituteADifferentLockToken() throws Exception {
    respond = exchange -> {
      exchange.getResponseHeaders().set("Lock-Token", "<" + OTHER + ">");
      reply(exchange, 200, lockXml().replace(TOKEN, OTHER));
    };
    assertThrows(IOException.class, () -> client.refreshLock(directory, TOKEN, directory));
    assertEquals("LOCK", request().method());
    assertTrue(requests.isEmpty());
  }

  @Test void expiredLeaseStopsWithoutReacquiringOrDeleting() throws Exception {
    respond = exchange -> reply(exchange, 412, null);
    SardineException failure = assertThrows(SardineException.class,
      () -> client.refreshLock(directory, TOKEN, directory));
    assertEquals(412, failure.getStatusCode());
    assertEquals("LOCK", request().method());
    assertTrue(requests.isEmpty());
  }

  @Test void lostDeleteResponseIsNotSilentlyRetried() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    respond = exchange -> { attempts.incrementAndGet(); exchange.close(); };
    assertThrows(IOException.class, () -> client.delete(directory + "old.ink.json", Map.of("If-Match", "\"v1\"")));
    assertEquals(1, attempts.get());
    assertEquals("DELETE", request().method());
    assertTrue(requests.isEmpty());
  }

  private String lockXml() {
    return """
      <d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
      <d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype>
      <d:depth>infinity</d:depth><d:timeout>Second-120</d:timeout>
      <d:locktoken><d:href>%s</d:href></d:locktoken>
      <d:lockroot><d:href>/book/</d:href></d:lockroot>
      </d:activelock></d:lockdiscovery></d:prop>
      """.formatted(TOKEN);
  }

  private WireRequest request() throws InterruptedException {
    WireRequest request = requests.poll(3, TimeUnit.SECONDS);
    assertNotNull(request, "Expected a request to the isolated loopback HTTP server");
    return request;
  }

  private static void reply(HttpExchange exchange, int status, String body) {
    try {
      if (body == null) exchange.sendResponseHeaders(status, -1);
      else {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    } catch (IOException failure) { throw new IllegalStateException(failure); }
    finally { exchange.close(); }
  }
}
