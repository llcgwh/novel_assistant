package com.novelwriting.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiControllerTest {
  static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path directory;
  MockMvc mvc;
  AiConfigStore config;
  AiRequestRegistry registry;
  AiProviderService provider;
  AiSuggestionService suggestions;
  String token;
  @BeforeEach void setup() throws Exception {
    config = new AiConfigStore(directory.resolve("config.json").toString(), directory.resolve("ai.key").toString(), new AiEndpointPolicy());
    registry = new AiRequestRegistry(); provider = mock(AiProviderService.class); suggestions = mock(AiSuggestionService.class);
    mvc = MockMvcBuilders.standaloneSetup(new AiController(config, provider, suggestions, registry))
      .addFilters(new AiLocalAccessFilter("http://localhost:3000")).build();
    token = session();
  }
  String session() throws Exception {
    String raw = mvc.perform(get("/api/ai/session").header("Origin", "http://localhost:3000"))
      .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
    return JSON.readTree(raw).path("token").asText();
  }
  MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Origin", "http://localhost:3000").header("X-AI-Session", token).contentType(MediaType.APPLICATION_JSON);
  }
  ObjectNode configure() {
    return config.update(JSON.createObjectNode().put("baseUrl", "https://provider.example/v1").put("model", "test-model")
      .put("endpointMode", "remote").put("apiKey", "dummy-key-never-echo").put("expectedRevision", "0"));
  }
  ObjectNode requestBody(String revision) {
    ObjectNode input = JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("action", "character.name").put("expectedConfigRevision", revision);
    input.putObject("context").put("targetId", 5); return input;
  }
  @Test void configurationResponseOmitsSecretsAndMissingSessionCannotAccessControllers() throws Exception {
    configure();
    String raw = mvc.perform(authorized(get("/api/ai/config"))).andExpect(status().isOk()).andExpect(jsonPath("$.hasKey").value(true))
      .andReturn().getResponse().getContentAsString();
    assertFalse(raw.contains("dummy-key")); assertFalse(raw.contains("encryptedKey")); assertFalse(raw.contains("apiKey"));
    mvc.perform(get("/api/ai/config").header("Origin", "http://localhost:3000")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ai/config").header("Origin", "https://untrusted.example").header("X-AI-Session", token)).andExpect(status().isForbidden());
  }
  @Test void suggestionsRequireDisplayedConfigurationAndDoNotWrapValidatedEnvelope() throws Exception {
    String revision = configure().path("revision").asText();
    ObjectNode stale = requestBody("0");
    mvc.perform(authorized(post("/api/novels/12/ai/suggestions")).content(stale.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_CONFIG_CHANGED"));
    verifyNoInteractions(suggestions, provider);
    ObjectNode input = requestBody(revision), envelope = JSON.createObjectNode().put("requestId", input.path("requestId").asText()).put("action", "character.name");
    envelope.putObject("result").putArray("candidates"); envelope.putObject("coverage"); envelope.putArray("sources");
    when(suggestions.suggest(eq(12L), eq("character.name"), any(), any())).thenReturn(envelope);
    mvc.perform(authorized(post("/api/novels/12/ai/suggestions")).content(input.toString())).andExpect(status().isOk())
      .andExpect(jsonPath("$.action").value("character.name")).andExpect(jsonPath("$.result.candidates").isArray()).andExpect(jsonPath("$.coverage").isMap());
    verify(suggestions).suggest(eq(12L), eq("character.name"), eq(input.path("context")), eq(UUID.fromString(input.path("requestId").asText())));
    mvc.perform(authorized(post("/api/novels/12/ai/suggestions")).content(input.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AI_DUPLICATE_REQUEST"));
  }
  @Test void freshnessCheckIsReadOnlyAndNeedsNoProviderConfiguration() throws Exception {
    ObjectNode input = JSON.createObjectNode().put("action", "character.name").put("snapshotId", "a".repeat(64)); input.putObject("context").put("targetId", 5);
    when(suggestions.contextCurrent(eq(12L), eq("character.name"), any(), eq("a".repeat(64)))).thenReturn(true);
    mvc.perform(authorized(post("/api/novels/12/ai/context-check")).content(input.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.current").value(true));
    assertFalse(config.status().path("configured").asBoolean()); verifyNoInteractions(provider);
  }
  @Test void referenceChapterMetadataNeedsLocalSessionButNoProviderCredentials() throws Exception {
    ObjectNode listing = JSON.createObjectNode().put("maxChars", 10000);
    listing.putArray("chapters").addObject().put("uid", "fixture-chapter").put("title", "第一章").put("wordCount", 12);
    when(suggestions.referenceChapters(12L)).thenReturn(listing);
    mvc.perform(authorized(get("/api/novels/12/ai/reference-chapters"))).andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.maxChars").value(10000))
      .andExpect(jsonPath("$.chapters[0].wordCount").value(12));
    mvc.perform(get("/api/novels/12/ai/reference-chapters").header("Origin", "http://localhost:3000")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/novels/12/ai/reference-chapters").header("Origin", "https://untrusted.example").header("X-AI-Session", token)).andExpect(status().isForbidden());
    mvc.perform(authorized(get("/api/novels/0/ai/reference-chapters"))).andExpect(status().isBadRequest());
    assertFalse(config.status().path("configured").asBoolean()); verifyNoInteractions(provider);
    verify(suggestions).referenceChapters(12L);
  }
  @Test void cancellationCannotCrossBrowserSessionsAndUnknownTopLevelFieldsAreRejected() throws Exception {
    UUID id = UUID.randomUUID(); registry.begin(id, token, "0");
    mvc.perform(post("/api/ai/cancel/" + id).header("Origin", "http://localhost:3000").header("X-AI-Session", session()))
      .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AI_REQUEST_NOT_FOUND"));
    mvc.perform(authorized(post("/api/ai/cancel/" + id))).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(true));
    ObjectNode input = requestBody("0").put("systemPrompt", "injected instruction");
    mvc.perform(authorized(post("/api/novels/12/ai/suggestions")).content(input.toString())).andExpect(status().isBadRequest());
    verifyNoInteractions(suggestions, provider);
  }
  @Test void totalDeadlineRejectsLateSuggestionAcrossWholeOperationWithoutWaitingInRealTime() throws Exception {
    AtomicLong clock = new AtomicLong(); registry = new AiRequestRegistry(clock::get);
    mvc = MockMvcBuilders.standaloneSetup(new AiController(config, provider, suggestions, registry))
      .addFilters(new AiLocalAccessFilter("http://localhost:3000")).build(); token = session();
    String revision = configure().path("revision").asText();
    when(suggestions.suggest(anyLong(), anyString(), any(), any())).thenAnswer(invocation -> {
      UUID id = invocation.getArgument(3);
      assertEquals(120000, registry.remainingMillis(id));
      clock.addAndGet(90_000_000_000L); registry.check(id); assertEquals(30000, registry.remainingMillis(id));
      clock.addAndGet(31_000_000_000L);
      return JSON.createObjectNode().put("unsafeLateCandidate", "must-never-return");
    });
    String result = mvc.perform(authorized(post("/api/novels/12/ai/suggestions")).content(requestBody(revision).toString()))
      .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("AI_TIMEOUT"))
      .andReturn().getResponse().getContentAsString();
    assertFalse(result.contains("must-never-return")); verifyNoInteractions(provider);
  }
}
