package com.novelwriting.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AiController {
  private final AiConfigStore configs;
  private final AiProviderService provider;
  private final AiSuggestionService suggestions;
  private final AiRequestRegistry requests;
  public AiController(AiConfigStore configs, AiProviderService provider, AiSuggestionService suggestions, AiRequestRegistry requests) {
    this.configs = configs; this.provider = provider; this.suggestions = suggestions; this.requests = requests;
  }

  @GetMapping("/api/ai/config") public JsonNode config() { return configs.status(); }
  @PutMapping("/api/ai/config") public JsonNode configure(@RequestBody JsonNode input) { return configs.update(input); }

  @PostMapping("/api/ai/test")
  public JsonNode test(@RequestBody JsonNode input, HttpServletRequest servlet) {
    AiConfigStore.exact(input, Set.of("expectedRevision"));
    String revision = AiConfigStore.text(input, "expectedRevision", 64);
    if (!configs.configuration().revision().equals(revision)) throw new AiProblem(409, "AI_CONFIG_CHANGED", "配置已经变化，请重新读取后测试");
    UUID requestId = UUID.randomUUID(); requests.begin(requestId, session(servlet), revision);
    try {
      JsonNode reply = provider.generate(requestId, "This is a connection test. Return exactly one JSON object: {\"ok\":true}. Do not include Markdown or any other content.", "Return the requested JSON object.", Math.min(provider.maxOutputTokens(), 512));
      if (reply.size() != 1 || !reply.path("ok").isBoolean() || !reply.path("ok").booleanValue()) throw new AiProblem(422, "AI_TEST_OUTPUT_INVALID", "已连接到模型，但模型未通过 JSON 输出测试");
      ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("success", true)
        .put("message", "连接及模型 JSON 输出测试成功；没有发送小说资料");
      var metadata = requests.metadata(requestId);
      if (metadata.model() != null) out.put("model", metadata.model());
      if (metadata.usage() != null) out.set("usage", metadata.usage());
      return out;
    } finally { requests.finish(requestId); }
  }

  @PostMapping("/api/novels/{novelId}/ai/suggestions")
  public JsonNode suggest(@PathVariable long novelId, @RequestBody JsonNode input, HttpServletRequest servlet) {
    AiConfigStore.exact(input, Set.of("action", "context", "requestId", "expectedConfigRevision"));
    if (novelId <= 0) throw AiProblem.bad("作品编号无效");
    String action = AiConfigStore.text(input, "action", 64), revision = AiConfigStore.text(input, "expectedConfigRevision", 64);
    UUID id = uuid(AiConfigStore.text(input, "requestId", 36));
    if (!input.path("context").isObject()) throw AiProblem.bad("AI 上下文必须是对象");
    if (!configs.configuration().revision().equals(revision)) throw new AiProblem(409, "AI_CONFIG_CHANGED", "AI 配置已变化，请重新读取并确认接收方后再生成");
    requests.begin(id, session(servlet), revision);
    try {
      JsonNode result = suggestions.suggest(novelId, action, input.get("context"), id);
      requests.check(id);
      var metadata = requests.metadata(id);
      if (result instanceof ObjectNode object) {
        if (metadata.model() != null) object.put("model", metadata.model());
        if (metadata.usage() != null) object.set("usage", metadata.usage());
      }
      return result;
    } finally { requests.finish(id); }
  }

  @PostMapping("/api/ai/cancel/{requestId}")
  public Map<String, Object> cancel(@PathVariable String requestId, HttpServletRequest servlet) {
    return Map.of("cancelled", requests.cancel(uuid(requestId), session(servlet)), "message", "取消仅停止本机等待及继续请求；已发送的请求仍可能计费");
  }
  @GetMapping("/api/novels/{novelId}/ai/reference-chapters")
  public JsonNode referenceChapters(@PathVariable long novelId) {
    if (novelId <= 0) throw AiProblem.bad("作品编号无效");
    return suggestions.referenceChapters(novelId);
  }

  @PostMapping("/api/novels/{novelId}/ai/context-check")
  public Map<String, Boolean> contextCheck(@PathVariable long novelId, @RequestBody JsonNode input) {
    AiConfigStore.exact(input, Set.of("action", "context", "snapshotId"));
    if (novelId <= 0 || !input.path("context").isObject()) throw AiProblem.bad("作品或 AI 上下文无效");
    return Map.of("current", suggestions.contextCurrent(novelId, AiConfigStore.text(input, "action", 64), input.get("context"), AiConfigStore.text(input, "snapshotId", 128)));
  }
  private static UUID uuid(String raw) {
    try { UUID value = UUID.fromString(raw); if (!value.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException(); return value; }
    catch (IllegalArgumentException invalid) { throw AiProblem.bad("AI 请求编号必须是完整 UUID"); }
  }
  private static String session(HttpServletRequest request) {
    Object value = request.getAttribute(AiLocalAccessFilter.SESSION_ATTRIBUTE);
    if (!(value instanceof String token)) throw new AiProblem(401, "AI_SESSION_REQUIRED", "缺少本机 AI 会话");
    return token;
  }
  @ExceptionHandler(AiProblem.class) public ResponseEntity<?> problem(AiProblem problem) {
    return ResponseEntity.status(problem.status()).body(Map.of("code", problem.code(), "message", problem.getMessage()));
  }
  @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> domain(ResponseStatusException problem) {
    // Context-domain messages are application-owned; never let arbitrary provider errors reach here.
    return ResponseEntity.status(problem.getStatusCode()).body(Map.of("code", "AI_CONTEXT_INVALID", "message", Objects.toString(problem.getReason(), "AI 上下文不可用")));
  }
  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed() {
    return ResponseEntity.badRequest().body(Map.of("code", "AI_INVALID_REQUEST", "message", "AI 请求格式无效"));
  }
  @ExceptionHandler(Exception.class) public ResponseEntity<?> failed() {
    return ResponseEntity.status(500).body(Map.of("code", "AI_REQUEST_FAILED", "message", "AI 请求未完成；没有采用任何内容，请检查配置后重试"));
  }
}
