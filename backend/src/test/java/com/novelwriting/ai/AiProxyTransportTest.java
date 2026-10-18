package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.*;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

class AiProxyTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  // Public test fixture only: self-signed fixture.ai.test, valid 2020-2120, with no production trust or credentials.
  private static final String FIXTURE = "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";

  @Test void proxyMustBeExplicitNumericLoopbackWithoutCredentialsOrOtherUrlParts() {
    assertEquals("", AiProxyTransport.normalize(null)); assertEquals("", AiProxyTransport.normalize(""));
    assertEquals("http://127.0.0.1:7897", AiProxyTransport.normalize("http://127.0.0.1:7897"));
    assertEquals("http://[0:0:0:0:0:0:0:1]:7897", AiProxyTransport.normalize("http://[::1]:7897"));
    for (String invalid : List.of("http://localhost:7897", "http://127.1:7897", "http://192.168.1.1:7897",
      "http://8.8.8.8:7897", "https://127.0.0.1:7897", "http://127.0.0.1", "http://127.0.0.1:0",
      "http://127.0.0.1:7897/", "http://127.0.0.1:7897?key=secret", "http://127.0.0.1:7897#fragment",
      "http://user:secret@127.0.0.1:7897", " http://127.0.0.1:7897", "http://[::1%25lo0]:7897"))
      assertEquals("AI_INVALID_REQUEST", assertThrows(AiProblem.class, () -> AiProxyTransport.normalize(invalid), invalid).code());
  }

  @Test void dnsAnswerAcceptsOnlyLiteralAddressesAndLeavesPublicRangeValidationToEndpointPolicy() throws Exception {
    InetAddress[] addresses = AiProxyTransport.parseDnsResponse(dns("8.8.4.4", "127.0.0.1", "8.8.4.4").toString().getBytes(StandardCharsets.UTF_8), "fixture.ai.test");
    assertEquals(2, addresses.length); assertEquals("8.8.4.4", addresses[0].getHostAddress());
    assertTrue(AiEndpointPolicy.isPublic(addresses[0])); assertFalse(AiEndpointPolicy.isPublic(addresses[1]));
    assertEquals("127.0.0.1", AiProxyTransport.resolve("127.0.0.1", "http://127.0.0.1:1")[0].getHostAddress());
    assertEquals("0:0:0:0:0:0:0:1", AiProxyTransport.resolve("[::1]", "http://127.0.0.1:1")[0].getHostAddress());
  }

  @Test void invalidTruncatedMismatchedAndOversizedDnsResponsesFailClosed() throws Exception {
    List<String> invalid = new ArrayList<>(List.of("[]", "{}", "{} {}", "{\"Status\":0,\"Status\":0}", "x".repeat(65537)));
    invalid.add(dns("not-an-address.invalid").toString());
    invalid.add(dns("127.1").toString());
    invalid.add(dns("8.8.8.8").put("TC", true).toString());
    invalid.add(dns("8.8.8.8").put("Status", 3).toString());
    invalid.add(dns("8.8.8.8").put("Status", "0").toString());
    invalid.add(dns("8.8.8.8").put("Status", 4294967296L).toString());
    ObjectNode wrongQuestion = dns("8.8.8.8"); ((ObjectNode) wrongQuestion.path("Question").get(0)).put("name", "other.invalid.");
    invalid.add(wrongQuestion.toString());
    ObjectNode empty = dns("8.8.8.8"); empty.putArray("Answer"); invalid.add(empty.toString());
    for (String response : invalid) assertEquals("AI_PROXY_DNS_FAILED", assertThrows(AiProblem.class,
      () -> AiProxyTransport.parseDnsResponse(response.getBytes(StandardCharsets.UTF_8), "fixture.ai.test")).code());
  }

  @Test void connectUsesPinnedNumericAddressWhileTlsSniHostAndCredentialsRemainInsideTheTunnel() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200)) {
      try (CloseableHttpClient client = client(proxy.url(), AiProxyTransport.socketFactory(proxy.url(), fixtureTls()))) {
        HttpPost post = post("fixture.ai.test");
        try (var response = client.execute(post)) { assertEquals(200, response.getStatusLine().getStatusCode()); }
      }
      proxy.finished.get(5, TimeUnit.SECONDS);
      assertEquals("CONNECT 8.8.4.4:443 HTTP/1.1\r\nHost: 8.8.4.4:443\r\n\r\n", proxy.tunnel.get());
      assertEquals("fixture.ai.test", proxy.sni.get());
      assertTrue(proxy.secureRequest.get().contains("Host: fixture.ai.test"));
      assertTrue(proxy.secureRequest.get().contains("Authorization: Bearer fake-key-only-for-test"));
      assertEquals("fictional manuscript test", proxy.secureBody.get());
      assertFalse(proxy.tunnel.get().contains("fake-key")); assertFalse(proxy.tunnel.get().contains("manuscript"));
      assertEquals(1, proxy.accepted.get());
    }
  }

  @Test void trustedCertificateForWrongHostnameIsRejectedBeforeSendingModelHeaders() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200, true);
         CloseableHttpClient client = client(proxy.url(), AiProxyTransport.socketFactory(proxy.url(), fixtureTls()))) {
      assertThrows(SSLPeerUnverifiedException.class, () -> client.execute(post("wrong.ai.test")));
      proxy.finished.get(5, TimeUnit.SECONDS);
      assertNull(proxy.secureRequest.get()); assertNull(proxy.secureBody.get()); assertEquals(1, proxy.accepted.get());
    }
  }

  @Test void defaultTlsStillRejectsUntrustedCertificatesBeforeSendingModelHeaders() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200, true);
         CloseableHttpClient client = client(proxy.url(), AiProxyTransport.socketFactory(proxy.url()))) {
      assertThrows(SSLHandshakeException.class, () -> client.execute(post("fixture.ai.test")));
      proxy.finished.get(5, TimeUnit.SECONDS);
      assertNull(proxy.secureRequest.get()); assertEquals(1, proxy.accepted.get());
    }
  }

  @Test void proxyRejectionIsSanitizedWithoutRetryOrDirectFallback() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(407);
         CloseableHttpClient client = client(proxy.url(), AiProxyTransport.socketFactory(proxy.url(), fixtureTls()))) {
      AiProblem problem = assertThrows(AiProblem.class, () -> client.execute(post("fixture.ai.test")));
      assertEquals("AI_PROXY_CONNECTION_FAILED", problem.code()); assertNull(problem.getCause());
      assertFalse(problem.getMessage().contains("proxy-private-details")); assertNull(proxy.secureRequest.get());
      proxy.finished.get(5, TimeUnit.SECONDS); assertEquals(1, proxy.accepted.get());
    }
  }

  @Test void cancelledLookupDoesNotStartANetworkRequest() {
    Thread.currentThread().interrupt();
    try { assertEquals("AI_CANCELLED", assertThrows(AiProblem.class,
      () -> AiProxyTransport.resolve("fixture.ai.test", "http://127.0.0.1:1")).code()); }
    finally { Thread.interrupted(); }
  }

  @Test void dohUsesPinnedBootstrapAndSendsOnlyTheHostnameWithoutModelSecrets() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200, dns("8.8.4.4").toString(), false)) {
      InetAddress[] addresses = AiProxyTransport.resolve("fixture.ai.test", proxy.url(), fixtureTls(), 3000);
      proxy.finished.get(5, TimeUnit.SECONDS);
      assertEquals("8.8.4.4", addresses[0].getHostAddress());
      assertEquals("CONNECT 8.8.8.8:443 HTTP/1.1\r\nHost: 8.8.8.8:443\r\n\r\n", proxy.tunnel.get());
      assertEquals("dns.google", proxy.sni.get());
      assertTrue(proxy.secureRequest.get().startsWith("GET /resolve?name=fixture.ai.test&type=A&edns_client_subnet=0.0.0.0%2F0 HTTP/1.1"));
      assertFalse(proxy.secureRequest.get().contains("Authorization")); assertEquals("", proxy.secureBody.get());
    }
  }

  @Test void slowDohResponseIsAbortedByTheTotalDeadline() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200, dns("8.8.4.4").toString(), true)) {
      long start = System.nanoTime();
      AiProblem failure = assertThrows(AiProblem.class, () -> AiProxyTransport.resolve("fixture.ai.test", proxy.url(), fixtureTls(), 600));
      assertEquals("AI_PROXY_DNS_TIMEOUT", failure.code());
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
      assertEquals(1, proxy.accepted.get());
    }
  }

  @Test void cancellationClosesABlockedDohResponseWithoutWaitingForTheDeadline() throws Exception {
    try (ProxyFixture proxy = new ProxyFixture(200, dns("8.8.4.4").toString(), true)) {
      ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch done = new CountDownLatch(1);
      try {
        Future<?> task = worker.submit(() -> {
          try { AiProxyTransport.resolve("fixture.ai.test", proxy.url(), fixtureTls(), 8000); }
          catch (Exception ignored) {} finally { done.countDown(); }
        });
        assertTrue(proxy.bodyStarted.await(3, TimeUnit.SECONDS)); task.cancel(true);
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(1, proxy.accepted.get());
      } finally { worker.shutdownNow(); }
    }
  }

  @Test void directDnsRecoveryPinsBootstrapAndSendsOnlyHostnameInsideVerifiedTls() throws Exception {
    try (ProxyFixture direct = new ProxyFixture(200, dns("8.8.4.4").toString(), false, false, true)) {
      InetAddress[] addresses = AiProxyTransport.encryptedDns("fixture.ai.test", directDnsSockets(direct, fixtureTls()), 3000, false);
      direct.finished.get(5, TimeUnit.SECONDS);
      assertEquals("8.8.4.4", addresses[0].getHostAddress()); assertNull(direct.tunnel.get());
      assertEquals("dns.google", direct.sni.get());
      assertTrue(direct.secureRequest.get().startsWith("GET /resolve?name=fixture.ai.test&type=A&edns_client_subnet=0.0.0.0%2F0 HTTP/1.1"));
      assertFalse(direct.secureRequest.get().contains("Authorization")); assertEquals("", direct.secureBody.get());
      assertEquals(1, direct.accepted.get());
    }
  }

  @Test void directDnsRecoveryFailureIsBoundedAndGivesVpnGuidanceWithoutFallback() throws Exception {
    try (ProxyFixture direct = new ProxyFixture(200, dns("8.8.4.4").toString(), true, false, true)) {
      long started = System.nanoTime();
      AiProblem error = assertThrows(AiProblem.class,
        () -> AiProxyTransport.encryptedDns("fixture.ai.test", directDnsSockets(direct, fixtureTls()), 600, false));
      assertEquals("AI_PUBLIC_DNS_TIMEOUT", error.code()); assertTrue(error.getMessage().contains("VPN"));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
      assertEquals(1, direct.accepted.get());
    }
    try (ProxyFixture direct = new ProxyFixture(200, "{}", false, true, true)) {
      AiProblem error = assertThrows(AiProblem.class,
        () -> AiProxyTransport.encryptedDns("fixture.ai.test", directDnsSockets(direct, SSLContext.getDefault()), 3000, false));
      assertEquals("AI_PUBLIC_DNS_FAILED", error.code()); assertNull(error.getCause());
      direct.finished.get(5, TimeUnit.SECONDS); assertNull(direct.secureRequest.get()); assertEquals(1, direct.accepted.get());
    }
  }

  private static SSLConnectionSocketFactory directDnsSockets(ProxyFixture fixture, SSLContext tls) {
    return new SSLConnectionSocketFactory(tls, SSLConnectionSocketFactory.getDefaultHostnameVerifier()) {
      @Override public Socket connectSocket(int timeout, Socket socket, HttpHost host, InetSocketAddress remote,
                                            InetSocketAddress local, HttpContext context) throws IOException {
        assertEquals("8.8.8.8", remote.getAddress().getHostAddress()); assertEquals(443, remote.getPort());
        assertEquals("dns.google", host.getHostName());
        return super.connectSocket(timeout, socket, host, new InetSocketAddress(InetAddress.getLoopbackAddress(), fixture.server.getLocalPort()), local, context);
      }
    };
  }

  private static ObjectNode dns(String... addresses) {
    ObjectNode result = JSON.createObjectNode().put("Status", 0).put("TC", false);
    result.putArray("Question").addObject().put("name", "fixture.ai.test.").put("type", 1);
    var answer = result.putArray("Answer");
    answer.addObject().put("name", "fixture.ai.test.").put("type", 5).put("data", "cdn.fixture.ai.test.");
    for (String address : addresses) answer.addObject().put("name", "cdn.fixture.ai.test.").put("type", 1).put("data", address);
    return result;
  }

  private static SSLContext fixtureTls() throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(new ByteArrayInputStream(Base64.getDecoder().decode(FIXTURE)), "fixture-only".toCharArray());
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "fixture-only".toCharArray());
    TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
    SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), trust.getTrustManagers(), null); return context;
  }

  private static CloseableHttpClient client(String proxy, SSLConnectionSocketFactory factory) {
    return HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
      .setSSLSocketFactory(factory).setDnsResolver(host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,4,4})}).build();
  }
  private static HttpPost post(String host) {
    HttpPost post = new HttpPost("https://" + host + "/v1/chat/completions");
    post.setHeader("Authorization", "Bearer fake-key-only-for-test");
    post.setEntity(new StringEntity("fictional manuscript test", StandardCharsets.UTF_8)); return post;
  }
  private static String headers(InputStream input) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream(); int matched = 0; byte[] end = {'\r','\n','\r','\n'};
    while (result.size() < 8192) {
      int next = input.read(); if (next < 0) return null;
      result.write(next); matched = next == end[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
      if (matched == 4) return result.toString(StandardCharsets.US_ASCII);
    }
    throw new IOException("Fixture headers too large");
  }

  private static class ProxyFixture implements AutoCloseable {
    final ServerSocket server;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final AtomicInteger accepted = new AtomicInteger();
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final AtomicReference<String> tunnel = new AtomicReference<>(), sni = new AtomicReference<>(), secureRequest = new AtomicReference<>(), secureBody = new AtomicReference<>();
    final Future<?> finished;
    ProxyFixture(int status) throws Exception { this(status, "{}", false, false); }
    ProxyFixture(int status, boolean expectedTlsRejection) throws Exception { this(status, "{}", false, expectedTlsRejection); }
    ProxyFixture(int status, String responseBody, boolean slowBody) throws Exception { this(status, responseBody, slowBody, false); }
    ProxyFixture(int status, String responseBody, boolean slowBody, boolean expectedTlsRejection) throws Exception {
      this(status, responseBody, slowBody, expectedTlsRejection, false);
    }
    ProxyFixture(int status, String responseBody, boolean slowBody, boolean expectedTlsRejection, boolean direct) throws Exception {
      server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127,0,0,1})); server.setSoTimeout(5000);
      SSLContext tls = fixtureTls();
      finished = executor.submit(() -> {
        try (Socket plain = server.accept()) {
          accepted.incrementAndGet(); plain.setSoTimeout(5000);
          if (!direct) {
            tunnel.set(headers(plain.getInputStream()));
            plain.getOutputStream().write(("HTTP/1.1 " + status + " " + (status == 200 ? "Connection established" : "proxy-private-details") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            plain.getOutputStream().flush(); if (status != 200) return;
          }
          try (SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(plain, "fixture.ai.test", 443, false)) {
            secure.setUseClientMode(false); secure.startHandshake();
            for (SNIServerName name : ((ExtendedSSLSession) secure.getSession()).getRequestedServerNames())
              if (name instanceof SNIHostName hostname) sni.set(hostname.getAsciiName());
            String request = headers(secure.getInputStream()); if (request == null) return; secureRequest.set(request);
            int length = 0;
            for (String line : request.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            secureBody.set(new String(secure.getInputStream().readNBytes(length), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            secure.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            secure.getOutputStream().flush(); bodyStarted.countDown();
            if (slowBody) {
              for (byte value : body) {
                secure.getOutputStream().write(value); secure.getOutputStream().flush();
                try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
              }
            } else { secure.getOutputStream().write(body); secure.getOutputStream().flush(); }
          } catch (IOException failure) {
            // A peer rejecting our test certificate may send a TLS alert, EOF, reset, or broken pipe.
            // Only the two explicit rejection tests accept this, and only before model headers arrive.
            if (!expectedTlsRejection || secureRequest.get() != null
              || !(failure instanceof SSLException || failure instanceof EOFException || failure instanceof SocketException)) throw failure;
          }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
      });
    }
    String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
    @Override public void close() throws Exception { server.close(); executor.shutdownNow(); }
  }
}
