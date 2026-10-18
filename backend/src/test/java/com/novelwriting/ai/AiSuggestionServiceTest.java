package com.novelwriting.ai;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class AiSuggestionServiceTest {
  @TempDir Path directory;

  @Test void everyCreativeActionUsesLocalHttpAndOnlyWritingNeedsIndependentReview() throws Exception {
    AiContextService contexts = mock(AiContextService.class);
    var data = JSON.createObjectNode().put("authorRequest", "仅用于本机测试的虚构资料");
    var snapshot = new AiContextService.Snapshot(data, JSON.createObjectNode(), JSON.createArrayNode(), Set.of("fixture:one"), "a".repeat(64));
    when(contexts.capture(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
    AtomicInteger generated = new AtomicInteger(), reviewed = new AtomicInteger();
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    AtomicReference<JsonNode> originalWritingMessages = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        JsonNode task = JSON.readTree(request.path("messages").get(1).path("content").asText());
        ObjectNode output;
        if (task.has("candidateOutput")) {
          int reviewNumber = reviewed.incrementAndGet();
          assertEquals(512, request.path("max_tokens").asInt());
          assertEquals(3, task.path("candidateOutput").size());
          output = JSON.createObjectNode().put("allowed", reviewNumber == 2);
          var found = output.putArray("issues"); if (reviewNumber == 1) found.add("dialogue");
        } else {
          generated.incrementAndGet();
          assertEquals(data, task.path("context"));
          assertEquals(parse("[\"fixture:one\"]"), task.path("allowedSourceRefs"));
          var action = AiActions.require(task.path("action").asText());
          if (action.writing()) {
            JsonNode messages = request.path("messages");
            if (messages.size() == 2) originalWritingMessages.set(messages.deepCopy());
            else {
              assertEquals(4, messages.size());
              assertEquals(originalWritingMessages.get().get(0), messages.get(0));
              assertEquals(originalWritingMessages.get().get(1), messages.get(1));
              assertEquals(fixtureOutput(action), JSON.readTree(messages.get(2).path("content").asText()));
              assertTrue(messages.get(3).path("content").asText().contains("对白"));
            }
          }
          output = fixtureOutput(action);
        }
        byte[] bytes = AiProviderServiceTest.envelope(output.toString(), "stop").toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
      } catch (Throwable failure) { serverFailure.set(failure); exchange.sendResponseHeaders(500, -1); }
      finally { exchange.close(); }
    });
    server.start();
    AiEndpointPolicy endpoints = new AiEndpointPolicy();
    AiConfigStore configs = new AiConfigStore(directory.resolve("config.json").toString(), directory.resolve("key").toString(), endpoints);
    configs.update(JSON.createObjectNode().put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
      .put("endpointMode", "local").put("model", "fixture-model").put("apiKey", "fixture-test-key").put("expectedRevision", "0"));
    AiRequestRegistry requests = new AiRequestRegistry();
    AiProviderService provider = new AiProviderService(configs, endpoints, requests);
    try {
      var service = new AiSuggestionService(contexts, provider);
      for (String name : AiActions.names()) {
        UUID id = UUID.randomUUID(); requests.begin(id, "fixture-session", configs.configuration().revision());
        JsonNode result = service.suggest(1, name, JSON.createObjectNode(), id);
        assertEquals(name, result.path("action").asText()); assertEquals(id.toString(), result.path("requestId").asText());
        assertEquals(fixtureOutput(AiActions.require(name)), result.path("result"));
        assertEquals(AiActions.require(name).writing() ? 4 : 1, requests.metadata(id).usage().path("requestCount").asInt());
        assertEquals(AiActions.require(name).writing() ? 2 : 1, result.path("attemptCount").asInt());
        assertEquals(AiActions.require(name).writing(), result.path("correctionApplied").asBoolean());
        assertEquals(AiActions.require(name).writing() ? "writing-boundary" : "structure-only", result.path("reviewMode").asText());
        assertFalse(result.toString().contains("fixture-test-key")); requests.finish(id);
      }
      assertNull(serverFailure.get()); assertEquals(15, generated.get()); assertEquals(2, reviewed.get());
      verify(contexts, times(14)).capture(eq(1L), any(), any(), anyInt());
    } finally { provider.shutdown(); server.stop(0); }
  }

  private static ObjectNode fixtureOutput(AiActions.Action action) {
    ObjectNode output = JSON.createObjectNode();
    var candidates = output.putArray("candidates");
    var directions = output.putArray("directions");
    var issues = output.putArray("issues");
    for (int i = 0; i < (action.review() ? 1 : 3); i++) {
      ObjectNode row;
      if (action.writing()) {
        row = directions.addObject().put("title", "方向" + i).put("goal", "明确人物目标").put("choice", "权衡承诺与职责")
          .put("consequence", "选择带来不同后果").put("risk", "可能损失信任").put("question", "当前证据是否充分？");
        row.putArray("assumptions").add("待作者确认的候选方向");
      } else if (action.review()) row = issues.addObject().put("title", "待确认事项").put("detail", "需核对动机和时序是否对应");
      else {
        row = candidates.addObject().put("title", "候选" + i).put("reason", "提供档案式备选");
        row.putObject("fields").put(action.field(), "候选资料" + i);
        row.putArray("assumptions").add("新增候选等待作者采用"); row.putArray("conflicts");
      }
      row.putArray("sourceRefs").add("fixture:one");
    }
    return output;
  }

  @Test void entireGenerationIsReadOnlyAndReviewUsesTheSameCancelableRequest() {
    AiContextService contexts = mock(AiContextService.class);
    AiProviderService provider = mock(AiProviderService.class);
    var snapshot = new AiContextService.Snapshot(JSON.createObjectNode(), JSON.createObjectNode(), JSON.createArrayNode(), Set.of("chapter:one"), "snapshot");
    when(contexts.capture(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
    when(provider.maxInputChars()).thenReturn(120000); when(provider.maxOutputTokens()).thenReturn(2000); when(provider.model()).thenReturn("test-model");
    UUID request = UUID.randomUUID();
    when(provider.generateConversation(eq(request), anyList(), anyInt())).thenReturn(AiOutputPolicyTest.directions());
    when(provider.generate(eq(request), anyString(), anyString(), anyInt())).thenReturn(parse("{\"allowed\":true,\"issues\":[]}"));
    var result = new AiSuggestionService(contexts, provider).suggest(1L, "writing.inspiration", JSON.createObjectNode(), request);
    assertEquals("snapshot", result.path("snapshotId").asText());
    verify(provider).generateConversation(eq(request), anyList(), anyInt());
    verify(provider).generate(eq(request), anyString(), anyString(), anyInt());
  }
  @Test void everyPromptDeclaresJsonIncludingTheShortReviewForProvidersUsingJsonOutputMode() {
    assertTrue(AiSuggestionService.reviewPrompt().toLowerCase().contains("json"));
    for (String name : AiActions.names()) assertTrue(AiSuggestionService.prompt(AiActions.require(name)).toLowerCase().contains("json"));
  }
  @Test void semanticFailureFeedsSpecificIssuesToOneCorrectionAndThenStops() {
    AiContextService contexts = mock(AiContextService.class);
    AiProviderService provider = fixtureProvider(contexts, Set.of("chapter:one"));
    UUID request = UUID.randomUUID();
    when(provider.generateConversation(eq(request), anyList(), anyInt())).thenReturn(AiOutputPolicyTest.directions());
    when(provider.generate(eq(request), anyString(), anyString(), anyInt())).thenReturn(parse("{\"allowed\":false,\"issues\":[\"dialogue\"]}"));
    var failure = assertThrows(AiProblem.class, () -> new AiSuggestionService(contexts, provider).suggest(1, "writing.inspiration", JSON.createObjectNode(), request));
    assertEquals("AI_CORRECTION_FAILED", failure.code()); assertFalse(failure.getMessage().contains("承诺"));
    assertTrue(failure.getMessage().contains("对白"));
    verify(provider, times(2)).generate(eq(request), anyString(), anyString(), anyInt());
    ArgumentCaptor<List<AiProviderService.Message>> histories = ArgumentCaptor.forClass(List.class);
    verify(provider, times(2)).generateConversation(eq(request), histories.capture(), eq(2000));
    assertHistory(histories.getAllValues(), AiOutputPolicyTest.directions().toString(), "对白");
    verify(contexts).capture(eq(1L), any(), any(), anyInt());
  }

  @Test void structureRepairKeepsOriginalContextRequestAndFailedCandidateWithoutSemanticReview() {
    AiContextService contexts = mock(AiContextService.class);
    AiProviderService provider = fixtureProvider(contexts, Set.of("fixture:one"));
    ObjectNode bad = fixtureOutput(AiActions.require("character.personality"));
    ((ObjectNode) bad.path("candidates").get(0)).putArray("sourceRefs").add("invented:source");
    ObjectNode corrected = fixtureOutput(AiActions.require("character.personality"));
    UUID request = UUID.randomUUID();
    when(provider.generateConversation(eq(request), anyList(), anyInt())).thenReturn(bad, corrected);
    JsonNode result = new AiSuggestionService(contexts, provider).suggest(1, "character.personality", parse("{\"authorRequest\":\"保留原始偏好\"}"), request);
    assertEquals(corrected, result.path("result")); assertEquals(2, result.path("attemptCount").asInt());
    assertTrue(result.path("correctionApplied").asBoolean()); assertEquals("structure-only", result.path("reviewMode").asText());
    assertTrue(result.path("correctionReason").asText().contains("资料标识"));
    assertFalse(result.path("correctionReason").asText().contains("invented"));
    ArgumentCaptor<List<AiProviderService.Message>> histories = ArgumentCaptor.forClass(List.class);
    verify(provider, times(2)).generateConversation(eq(request), histories.capture(), eq(2000));
    assertHistory(histories.getAllValues(), bad.toString(), "资料标识");
    verify(provider, never()).generate(any(), anyString(), anyString(), anyInt());
    verify(contexts).capture(eq(1L), any(), any(), anyInt());
  }

  @Test void malformedOrTruncatedAssistantOutputCanBeCorrectedOnceWithoutReturningItToBrowser() {
    for (String code : List.of("AI_INVALID_OUTPUT", "AI_OUTPUT_TRUNCATED")) {
      AiContextService contexts = mock(AiContextService.class);
      AiProviderService provider = fixtureProvider(contexts, Set.of("fixture:one"));
      String raw = "{\"privateIncompleteCandidate\":";
      when(provider.generateConversation(any(), anyList(), anyInt()))
        .thenThrow(new AiProblem(422, code, "JSON 不完整").correctionContent(raw))
        .thenReturn(fixtureOutput(AiActions.require("scene.description")));
      JsonNode result = new AiSuggestionService(contexts, provider).suggest(1, "scene.description", JSON.createObjectNode(), UUID.randomUUID());
      assertTrue(result.path("correctionApplied").asBoolean()); assertFalse(result.toString().contains("privateIncompleteCandidate"));
      ArgumentCaptor<List<AiProviderService.Message>> histories = ArgumentCaptor.forClass(List.class);
      verify(provider, times(2)).generateConversation(any(), histories.capture(), anyInt());
      assertHistory(histories.getAllValues(), raw, "JSON 不完整");
    }
  }

  @Test void transportAuthTimeoutCancellationRevisionAndBudgetFailuresNeverStartCorrection() {
    for (String code : List.of("AI_CONNECTION_FAILED", "AI_AUTH_REJECTED", "AI_TIMEOUT", "AI_CANCELLED", "AI_CONFIG_CHANGED", "AI_CONTEXT_TOO_LARGE", "AI_PROVIDER_LIMIT")) {
      AiContextService contexts = mock(AiContextService.class);
      AiProviderService provider = fixtureProvider(contexts, Set.of("fixture:one"));
      when(provider.generateConversation(any(), anyList(), anyInt())).thenThrow(new AiProblem(502, code, "固定诊断"));
      assertEquals(code, assertThrows(AiProblem.class, () -> new AiSuggestionService(contexts, provider).suggest(1, "character.personality", JSON.createObjectNode(), UUID.randomUUID())).code());
      verify(provider).generateConversation(any(), anyList(), anyInt());
      verify(provider, never()).generate(any(), anyString(), anyString(), anyInt());
    }
  }

  @Test void cancellationOrBudgetFailureBetweenAttemptsStopsBeforeAnyThirdGeneration() {
    for (String code : List.of("AI_CANCELLED", "AI_TIMEOUT", "AI_CONTEXT_TOO_LARGE", "AI_CONFIG_CHANGED")) {
      AiContextService contexts = mock(AiContextService.class);
      AiProviderService provider = fixtureProvider(contexts, Set.of("fixture:one"));
      when(provider.generateConversation(any(), anyList(), anyInt())).thenReturn(JSON.createObjectNode()).thenThrow(new AiProblem(409, code, "固定诊断"));
      assertEquals(code, assertThrows(AiProblem.class, () -> new AiSuggestionService(contexts, provider).suggest(1, "character.personality", JSON.createObjectNode(), UUID.randomUUID())).code());
      verify(provider, times(2)).generateConversation(any(), anyList(), anyInt());
    }
  }

  private static AiProviderService fixtureProvider(AiContextService contexts, Set<String> sources) {
    AiProviderService provider = mock(AiProviderService.class);
    when(contexts.capture(anyLong(), any(), any(), anyInt())).thenReturn(new AiContextService.Snapshot((ObjectNode) parse("{\"authorRequest\":\"保留原始偏好\"}"), JSON.createObjectNode(), JSON.createArrayNode(), sources, "snapshot"));
    when(provider.maxInputChars()).thenReturn(120000); when(provider.maxOutputTokens()).thenReturn(2000); when(provider.model()).thenReturn("fixture-model");
    return provider;
  }

  private static void assertHistory(List<List<AiProviderService.Message>> histories, String failedCandidate, String diagnostic) {
    assertEquals(2, histories.get(0).size()); assertEquals(4, histories.get(1).size());
    assertEquals(histories.get(0), histories.get(1).subList(0, 2));
    assertEquals("assistant", histories.get(1).get(2).role()); assertEquals(failedCandidate, histories.get(1).get(2).content());
    assertEquals("user", histories.get(1).get(3).role()); assertTrue(histories.get(1).get(3).content().contains(diagnostic));
  }
  @Test void freshnessCheckNeverUsesProviderOrNeedsItsConfiguration() {
    AiContextService contexts = mock(AiContextService.class);
    AiProviderService provider = mock(AiProviderService.class);
    String digest = "a".repeat(64);
    when(contexts.capture(anyLong(), any(), any(), anyInt())).thenReturn(new AiContextService.Snapshot(JSON.createObjectNode(), JSON.createObjectNode(), JSON.createArrayNode(), Set.of(), digest));
    var service = new AiSuggestionService(contexts, provider);
    assertTrue(service.contextCurrent(1, "character.name", JSON.createObjectNode(), digest));
    assertFalse(service.contextCurrent(1, "character.name", JSON.createObjectNode(), "b".repeat(64)));
    verifyNoInteractions(provider);
  }
}
