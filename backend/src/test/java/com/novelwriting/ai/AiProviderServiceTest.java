package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AiProviderServiceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path directory;
  HttpServer server;
  AiConfigStore store;
  AiRequestRegistry registry;
  AiProviderService provider;
  AtomicInteger calls = new AtomicInteger(), redirected = new AtomicInteger();
  AtomicReference<String> authorization = new AtomicReference<>(), posted = new AtomicReference<>();
  volatile int status = 200;
  volatile String output;
  volatile CountDownLatch entered, release;

  @BeforeEach void setup() throws Exception {
    output = envelope("{\"ok\":true}", "stop").toString();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      if (entered != null) entered.countDown();
      if (release != null) try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      if (status == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
      byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
      try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
      finally { exchange.close(); }
    });
    server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
    server.start();
    AiEndpointPolicy endpoints = new AiEndpointPolicy();
    store = new AiConfigStore(directory.resolve("config.json").toString(), directory.resolve("ai.key").toString(), endpoints);
    store.update(config("0"));
    registry = new AiRequestRegistry(); provider = new AiProviderService(store, endpoints, registry);
  }
  ObjectNode config(String revision) {
    return JSON.createObjectNode().put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
      .put("model", "dummy-model").put("endpointMode", "local").put("apiKey", "fake-test-key").put("expectedRevision", revision);
  }
  @AfterEach void cleanup() { if (release != null) release.countDown(); provider.shutdown(); server.stop(0); }
  UUID begin() { UUID id = UUID.randomUUID(); registry.begin(id, "session-a", store.configuration().revision()); return id; }
  static ObjectNode envelope(String content, String reason) {
    ObjectNode out = JSON.createObjectNode(); var choice = out.putArray("choices").addObject().put("finish_reason", reason);
    choice.putObject("message").put("role", "assistant").put("content", content);
    out.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 4).put("total_tokens", 14).put("secret", "never-return");
    return out;
  }
  @Test void sendsMinimalBoundedPayloadAndAccumulatesTwoCallsWithoutLeakingUsageFields() throws Exception {
    UUID id = begin();
    assertTrue(provider.generate(id, "Return JSON", "No novel content", 3000).path("ok").asBoolean());
    assertEquals("Bearer fake-test-key", authorization.get());
    JsonNode body = JSON.readTree(posted.get());
    assertEquals(4, body.size()); assertEquals("dummy-model", body.path("model").asText()); assertEquals(2000, body.path("max_tokens").asInt());
    assertFalse(body.has("tools")); assertFalse(body.has("response_format")); assertEquals(2, body.path("messages").size());
    provider.generate(id, "Review only", "candidate", 128);
    assertEquals(28, registry.metadata(id).usage().path("total_tokens").asInt());
    assertEquals(2, registry.metadata(id).usage().path("requestCount").asInt()); assertFalse(registry.metadata(id).usage().has("secret"));
    assertEquals("AI_CALL_LIMIT", assertThrows(AiProblem.class, () -> provider.generate(id, "again", "again", 128)).code());
    assertEquals(2, calls.get());
  }
  @Test void repairConversationSendsPriorAssistantAndFeedbackAndCountsMalformedCallAsIncompleteUsage() throws Exception {
    UUID id = begin(); provider.prepareSuggestion(id, false);
    output = "not a provider JSON envelope";
    assertEquals("AI_INVALID_OUTPUT", assertThrows(AiProblem.class, () -> provider.generateConversation(id,
      List.of(new AiProviderService.Message("system", "Return JSON"), new AiProviderService.Message("user", "original context")), 128)).code());
    output = envelope("{\"ok\":true}", "stop").toString();
    var history = List.of(new AiProviderService.Message("system", "Return JSON"), new AiProviderService.Message("user", "original context"),
      new AiProviderService.Message("assistant", "failed candidate"), new AiProviderService.Message("user", "specific validation feedback"));
    assertTrue(provider.generateConversation(id, history, 128).path("ok").asBoolean());
    JsonNode sent = JSON.readTree(posted.get()).path("messages");
    assertEquals(4, sent.size()); assertEquals("assistant", sent.get(2).path("role").asText());
    assertEquals("failed candidate", sent.get(2).path("content").asText());
    assertEquals("specific validation feedback", sent.get(3).path("content").asText());
    assertEquals(2, registry.metadata(id).usage().path("requestCount").asInt());
    assertEquals(14, registry.metadata(id).usage().path("total_tokens").asInt());
    assertTrue(registry.metadata(id).usage().path("incomplete").asBoolean());
  }
  @Test void writingOperationHasFourCallHardLimitAndBudgetCannotBeReset() {
    UUID id = begin(); provider.prepareSuggestion(id, true);
    for (int i = 0; i < 4; i++) provider.generate(id, "Return JSON", "fixture", 128);
    assertEquals(4, calls.get()); assertEquals(4, registry.metadata(id).usage().path("requestCount").asInt());
    assertEquals("AI_CALL_LIMIT", assertThrows(AiProblem.class, () -> provider.generate(id, "Return JSON", "fixture", 128)).code());
    assertThrows(AiProblem.class, () -> provider.prepareSuggestion(id, true)); assertEquals(4, calls.get());
    assertEquals(4, registry.metadata(id).usage().path("requestCount").asInt());
  }
  @Test void fullCorrectionHistoryMustFitOriginalBudgetAndCannotSendAfterCancellation() {
    UUID id = begin(); provider.prepareSuggestion(id, false);
    provider.generate(id, "Return JSON", "fixture", 128);
    var history = List.of(new AiProviderService.Message("system", "system"), new AiProviderService.Message("user", "x".repeat(100000)),
      new AiProviderService.Message("assistant", "y".repeat(20000)), new AiProviderService.Message("user", "feedback"));
    AiProblem budget = assertThrows(AiProblem.class, () -> provider.generateConversation(id, history, 128));
    assertEquals("AI_CONTEXT_TOO_LARGE", budget.code()); assertTrue(budget.getMessage().contains("未发送第二轮"));
    assertTrue(budget.getMessage().contains("首轮请求可能已计费"));
    assertEquals(1, calls.get());
    registry.cancel(id, "session-a");
    assertEquals("AI_CANCELLED", assertThrows(AiProblem.class, () -> provider.generateConversation(id,
      List.of(new AiProviderService.Message("user", "small correction")), 128)).code());
    assertEquals(1, calls.get());
  }
  @Test void redirectsAndServerErrorsAreNotRetriedAndTheirBodiesAreNeverExposed() {
    status = 302; output = "provider secret fake-test-key with manuscript";
    AiProblem redirect = assertThrows(AiProblem.class, () -> provider.generate(begin(), "system", "user", 128));
    assertEquals("AI_REDIRECT_BLOCKED", redirect.code()); assertEquals(0, redirected.get()); assertEquals(1, calls.get());
    status = 500;
    AiProblem failure = assertThrows(AiProblem.class, () -> provider.generate(begin(), "system", "user", 128));
    assertEquals(2, calls.get()); assertFalse(failure.getMessage().contains("fake-test-key")); assertNull(failure.getCause());
  }
  @Test void cancellationIsOwnedBySessionAndStopsInFlightRequestWithoutRetry() throws Exception {
    entered = new CountDownLatch(1); release = new CountDownLatch(1); UUID id = begin();
    ExecutorService work = Executors.newSingleThreadExecutor();
    try {
      Future<JsonNode> result = work.submit(() -> provider.generate(id, "system", "user", 128));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(404, assertThrows(AiProblem.class, () -> registry.cancel(id, "session-b")).status());
      assertTrue(registry.cancel(id, "session-a"));
      ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
      assertEquals("AI_CANCELLED", ((AiProblem) failure.getCause()).code()); assertEquals(1, calls.get());
      assertEquals("AI_CANCELLED", assertThrows(AiProblem.class, () -> provider.generate(id, "system", "user", 128)).code());
    } finally { release.countDown(); work.shutdownNow(); }
  }
  @Test void staleConfigurationDuplicateIdentityAndOversizedContextDoNotReachProvider() {
    UUID stale = begin(); store.update(config(store.status().path("revision").asText()).put("model", "changed-model"));
    assertEquals("AI_CONFIG_CHANGED", assertThrows(AiProblem.class, () -> provider.generate(stale, "system", "user", 128)).code());
    UUID id = begin();
    assertEquals("AI_DUPLICATE_REQUEST", assertThrows(AiProblem.class, () -> registry.begin(id, "session-a", store.configuration().revision())).code());
    assertEquals("AI_CONTEXT_TOO_LARGE", assertThrows(AiProblem.class, () -> provider.generate(id, "s", "x".repeat(120000), 128)).code());
    assertEquals(0, calls.get());
  }
  @Test void outputMustBeSingleCompleteJsonObjectWithoutToolsOrDuplicateKeys() {
    for (String content : List.of("```json\n{}\n```", "[]", "{} {}", "{\"x\":1,\"x\":2}", "{broken"))
      assertEquals("AI_INVALID_OUTPUT", assertThrows(AiProblem.class, () -> AiProviderService.parseResult(envelope(content, "stop"))).code());
    assertThrows(AiProblem.class, () -> AiProviderService.parseResult(envelope("{}", "length")));
    ObjectNode tools = envelope("{}", "stop"); ((ObjectNode) tools.path("choices").get(0).path("message")).putArray("tool_calls").addObject().put("type", "function");
    assertThrows(AiProblem.class, () -> AiProviderService.parseResult(tools));
    output = "unparseable private server response";
    assertEquals("AI_INVALID_OUTPUT", assertThrows(AiProblem.class, () -> provider.generate(begin(), "system", "user", 128)).code());
  }
  @Test void officialDeepSeekUsesBoundedNonThinkingJsonForBothGenerationAndReview() {
    for (String base : List.of("https://api.deepseek.com", "https://api.deepseek.com/v1"))
      for (String model : List.of("deepseek-flash", "deepseek-v4-pro"))
        for (int limit : List.of(2000, 512)) {
          ObjectNode body = JSON.createObjectNode().put("model", model).put("max_tokens", limit);
          var config = new AiConfigStore.Configuration("revision", base, model, "remote", 120000, 2000, "max_tokens", "");
          AiProviderService.applyStructuredOutputProfile(body, config);
          assertEquals("disabled", body.path("thinking").path("type").asText());
          assertEquals("json_object", body.path("response_format").path("type").asText());
          assertEquals(limit, body.path("max_tokens").asInt());
        }
    for (String base : List.of("https://api.deepseek.com.attacker.example", "https://proxy.example", "http://127.0.0.1:11434/v1", "https://api.deepseek.com:8443", "https://api.deepseek.com/other")) {
      ObjectNode body = JSON.createObjectNode();
      AiProviderService.applyStructuredOutputProfile(body, new AiConfigStore.Configuration("revision", base, "deepseek-flash", "remote", 120000, 2000, "max_tokens", ""));
      assertTrue(body.isEmpty(), base);
    }
    ObjectNode unknownModel = JSON.createObjectNode();
    AiProviderService.applyStructuredOutputProfile(unknownModel, new AiConfigStore.Configuration("revision", "https://api.deepseek.com", "unverified-model", "remote", 120000, 2000, "max_tokens", ""));
    assertTrue(unknownModel.isEmpty());
  }
  @Test void truncatedThinkingOutputIsDiagnosedWithoutLeakingContentOrAcceptingIncompleteJson() {
    ObjectNode response = envelope("{\"incomplete\":", "length");
    ((ObjectNode) response.path("choices").get(0).path("message")).put("reasoning_content", "private model reasoning and source content");
    AiProblem problem = assertThrows(AiProblem.class, () -> AiProviderService.parseResult(response));
    assertEquals("AI_OUTPUT_TRUNCATED", problem.code()); assertTrue(problem.getMessage().contains("思考过程"));
    assertFalse(problem.getMessage().contains("private")); assertFalse(problem.getMessage().contains("incomplete"));
    assertEquals("AI_OUTPUT_TRUNCATED", assertThrows(AiProblem.class, () -> AiProviderService.parseResult(envelope("{}", "length"))).code());
  }
  @Test void sendsOnlyExplicitlyChosenTokenParameterWithoutAutomaticFallback() throws Exception {
    store.update(config(store.status().path("revision").asText()).put("tokenParameter", "max_completion_tokens"));
    provider.generate(begin(), "system", "user", 256);
    JsonNode body = JSON.readTree(posted.get());
    assertTrue(body.has("max_completion_tokens")); assertFalse(body.has("max_tokens")); assertEquals(256, body.path("max_completion_tokens").asInt());
    status = 400;
    assertEquals("AI_PROVIDER_INCOMPATIBLE", assertThrows(AiProblem.class, () -> provider.generate(begin(), "system", "user", 128)).code());
    assertEquals(2, calls.get());
  }
  @Test void transportUsesValidatedPinnedAddressesInsteadOfResolvingHostnameAgain() {
    AiEndpointPolicy pinned = new AiEndpointPolicy() {
      @Override public Target resolve(String baseUrl, String mode) {
        try {
          return new Target(URI.create("http://unresolvable-ai.invalid:" + server.getAddress().getPort() + "/v1/chat/completions"),
            new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})});
        } catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
      }
    };
    AiProviderService client = new AiProviderService(store, pinned, registry);
    try { assertTrue(client.generate(begin(), "system", "user", 128).path("ok").asBoolean()); assertEquals(1, calls.get()); }
    finally { client.shutdown(); }
  }
  @Test void failedProxyResolutionDoesNotSendModelRequestOrFallBackAndCanSwitchBackToDirect() {
    AiEndpointPolicy endpoints = new AiEndpointPolicy() {
      @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) {
        assertEquals("provider.example", host); assertEquals("http://127.0.0.1:7897", proxyUrl);
        throw new AiProblem(502, "AI_PROXY_CONNECTION_FAILED", "本机代理连接失败");
      }
    };
    var client = new AiProviderService(store, endpoints, registry);
    try {
      store.update(config(store.status().path("revision").asText()).put("baseUrl", "https://provider.example")
        .put("endpointMode", "remote").put("proxyUrl", "http://127.0.0.1:7897"));
      UUID id = begin();
      assertEquals("AI_PROXY_CONNECTION_FAILED", assertThrows(AiProblem.class, () -> client.generate(id, "system", "user", 128)).code());
      assertEquals(0, calls.get()); assertNull(registry.metadata(id).usage());
      store.update(config(store.status().path("revision").asText()).put("proxyUrl", ""));
      assertTrue(client.generate(begin(), "system", "user", 128).path("ok").asBoolean());
      assertEquals(1, calls.get());
    } finally { client.shutdown(); }
  }
  @Test void cancellationJustBeforeDnsAttachmentStillInterruptsLookup() throws Exception {
    CountDownLatch lookupStarted = new CountDownLatch(1), interrupted = new CountDownLatch(1);
    AiEndpointPolicy delayed = new AiEndpointPolicy() {
      @Override public Target resolve(String baseUrl, String mode, String proxyUrl) {
        lookupStarted.countDown();
        try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
        catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        throw new AiProblem(409, "AI_CANCELLED", "cancelled");
      }
    };
    AiRequestRegistry race = new AiRequestRegistry() {
      @Override public void attach(UUID id, Runnable abort) {
        try { assertTrue(lookupStarted.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
        cancel(id, "session-a");
        super.attach(id, abort);
      }
    };
    UUID id = UUID.randomUUID(); race.begin(id, "session-a", store.configuration().revision());
    var client = new AiProviderService(store, delayed, race);
    try {
      assertEquals("AI_CANCELLED", assertThrows(AiProblem.class, () -> client.generate(id, "system", "user", 128)).code());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS)); assertEquals(0, calls.get());
    } finally { client.shutdown(); }
  }
  @Test void cancellingDuringProxyConnectIsReportedAsCancelledAndDoesNotRetry() throws Exception {
    try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      proxy.setSoTimeout(3000);
      var enteredTunnel = new CountDownLatch(1);
      var worker = Executors.newFixedThreadPool(2);
      AiEndpointPolicy endpoints = new AiEndpointPolicy() {
        @Override protected InetAddress[] lookupViaProxy(String host, String proxyUrl) {
          try { return new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})}; }
          catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
      };
      var client = new AiProviderService(store, endpoints, registry);
      Future<?> tunnel = worker.submit(() -> {
        try (Socket socket = proxy.accept()) {
          socket.setSoTimeout(3000);
          if (socket.getInputStream().read() >= 0) enteredTunnel.countDown();
          while (socket.getInputStream().read() >= 0) { /* Wait for client cancellation; never reply or forward. */ }
        } catch (java.io.IOException closed) { /* Test cleanup or cancellation closes the socket. */ }
      });
      try {
        store.update(config(store.status().path("revision").asText()).put("baseUrl", "https://provider.example")
          .put("endpointMode", "remote").put("proxyUrl", "http://127.0.0.1:" + proxy.getLocalPort()));
        UUID id = begin();
        Future<JsonNode> request = worker.submit(() -> client.generate(id, "system", "user", 128));
        assertTrue(enteredTunnel.await(3, TimeUnit.SECONDS));
        registry.cancel(id, "session-a");
        ExecutionException error = assertThrows(ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
        assertEquals("AI_CANCELLED", ((AiProblem) error.getCause()).code());
        tunnel.get(3, TimeUnit.SECONDS); assertEquals(0, calls.get());
      } finally { client.shutdown(); worker.shutdownNow(); }
    }
  }
  @Test void stuckNativeDnsCannotBlockRecoveryThroughProxy() throws Exception {
    var started = new CountDownLatch(2); var releaseDns = new CountDownLatch(1);
    AiEndpointPolicy stuck = new AiEndpointPolicy() {
      @Override public Target resolve(String baseUrl, String mode, String proxyUrl) {
        if (!proxyUrl.isEmpty()) return new Target(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"), new InetAddress[]{InetAddress.getLoopbackAddress()});
        started.countDown();
        boolean done = false;
        while (!done) try { releaseDns.await(); done = true; } catch (InterruptedException ignored) { /* Native resolver may ignore cancellation. */ }
        throw new AiProblem(502, "AI_DNS_FAILED", "DNS unavailable");
      }
    };
    var client = new AiProviderService(store, stuck, registry);
    var worker = Executors.newFixedThreadPool(2);
    try {
      UUID a = begin(), b = begin();
      Future<JsonNode> first = worker.submit(() -> client.generate(a, "system", "user", 128));
      Future<JsonNode> second = worker.submit(() -> client.generate(b, "system", "user", 128));
      assertTrue(started.await(3, TimeUnit.SECONDS));
      registry.cancel(a, "session-a"); registry.cancel(b, "session-a");
      assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
      store.update(config(store.status().path("revision").asText()).put("baseUrl", "https://provider.example")
        .put("endpointMode", "remote").put("proxyUrl", "http://127.0.0.1:7897"));
      assertTrue(client.generate(begin(), "system", "user", 128).path("ok").asBoolean());
      assertEquals(1, calls.get());
    } finally { releaseDns.countDown(); client.shutdown(); worker.shutdownNow(); }
  }
}
