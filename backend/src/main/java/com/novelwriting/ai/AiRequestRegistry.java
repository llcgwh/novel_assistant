package com.novelwriting.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Cancellation ownership and short-lived retry identities; no prompts or generated text retained. */
@Component
public class AiRequestRegistry {
  private static final class Request {
    final String owner;
    final Instant created = Instant.now();
    final long started;
    final String revision;
    boolean cancelled, finished;
    int calls, maxCalls = 2;
    boolean suggestionPrepared;
    Runnable abort;
    String model;
    JsonNode usage;
    Request(String owner, String revision, long started) { this.owner = owner; this.revision = revision; this.started = started; }
  }
  public record Metadata(String model, JsonNode usage) {}
  private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
  private final LongSupplier nanoClock;
  public AiRequestRegistry() { this(System::nanoTime); }
  AiRequestRegistry(LongSupplier nanoClock) { this.nanoClock = nanoClock; }

  public synchronized void begin(UUID id, String owner, String revision) {
    if (id == null || owner == null || owner.isBlank()) throw AiProblem.bad("缺少 AI 请求身份");
    requests.entrySet().removeIf(e -> e.getValue().finished && e.getValue().created.isBefore(Instant.now().minus(Duration.ofMinutes(30))));
    if (requests.size() >= 512) throw new AiProblem(429, "AI_REQUEST_LIMIT", "AI 请求过于频繁，请稍后再试");
    if (requests.putIfAbsent(id, new Request(owner, revision, nanoClock.getAsLong())) != null) throw new AiProblem(409, "AI_DUPLICATE_REQUEST", "此请求编号已使用；为避免重复计费，没有再次发送");
  }
  public synchronized void attach(UUID id, Runnable abort) {
    Request r = required(id);
    if (r.cancelled) throw cancelled();
    r.abort = abort;
  }
  public synchronized void check(UUID id) {
    Request r = required(id);
    if (r.cancelled) throw cancelled();
    if (remainingMillis(id) <= 0) throw new AiProblem(504, "AI_TIMEOUT", "AI 请求已超过总时限；可能已计费，未自动重试");
  }
  public synchronized long remainingMillis(UUID id) { return 120000 - (nanoClock.getAsLong() - required(id).started) / 1_000_000; }
  public synchronized void verifyRevision(UUID id, String revision) {
    if (!Objects.equals(required(id).revision, revision)) throw new AiProblem(409, "AI_CONFIG_CHANGED", "AI 配置已变化，请重新读取并确认接收方后再生成");
  }
  public synchronized void prepareSuggestion(UUID id, boolean writing) {
    Request r = required(id); check(id);
    if (r.calls != 0 || r.suggestionPrepared) throw AiProblem.bad("AI 操作已经开始，不能重新设定调用预算");
    r.suggestionPrepared = true;
    r.maxCalls = writing ? 4 : 2;
  }
  public synchronized void startCall(UUID id) {
    Request r = required(id); check(id);
    if (r.calls >= r.maxCalls) throw new AiProblem(429, "AI_CALL_LIMIT", "本次 AI 操作已达到调用上限，不再发送请求");
    r.calls++;
  }
  public synchronized boolean cancel(UUID id, String owner) {
    Request r = requests.get(id);
    if (r == null || !r.owner.equals(owner)) throw new AiProblem(404, "AI_REQUEST_NOT_FOUND", "没有找到当前会话的 AI 请求");
    if (r.finished) return false;
    r.cancelled = true;
    if (r.abort != null) r.abort.run();
    return true;
  }
  public synchronized void metadata(UUID id, String model, JsonNode usage) {
    Request r = required(id); r.model = model;
    var total = JsonNodeFactory.instance.objectNode();
    if (r.usage != null && r.usage.isObject()) total.setAll((com.fasterxml.jackson.databind.node.ObjectNode) r.usage);
    if (usage != null) for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
      if (usage.has(key)) {
        long before = total.path(key).asLong(0), addition = usage.path(key).asLong(0);
        total.put(key, before > Long.MAX_VALUE - addition ? Long.MAX_VALUE : before + addition);
      }
    }
    total.put("requestCount", r.calls);
    if (usage == null) total.put("incomplete", true);
    r.usage = total;
  }
  public synchronized Metadata metadata(UUID id) {
    Request r = required(id); return new Metadata(r.model, r.usage);
  }
  public synchronized void finish(UUID id) { Request r = requests.get(id); if (r != null) { r.finished = true; r.abort = null; } }
  private Request required(UUID id) {
    Request r = requests.get(id);
    if (r == null || r.finished) throw AiProblem.bad("AI 请求未登记或已经结束");
    return r;
  }
  private static AiProblem cancelled() { return new AiProblem(409, "AI_CANCELLED", "请求已取消；已发出的请求仍可能由服务方计费"); }
}
