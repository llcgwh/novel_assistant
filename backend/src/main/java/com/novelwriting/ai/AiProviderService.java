package com.novelwriting.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.*;
import org.springframework.stereotype.Service;

@Service
public class AiProviderService {
  private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final AiConfigStore configs;
  private final AiEndpointPolicy endpoints;
  private final AiRequestRegistry requests;
  private final Semaphore slots = new Semaphore(2);
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ai-request-deadline"); t.setDaemon(true); return t; });
  private final ExecutorService dns = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
    r -> { Thread t = new Thread(r, "ai-dns-lookup"); t.setDaemon(true); return t; });
  // Native DNS can ignore interruption. It must not prevent recovery through the explicitly selected proxy.
  private final ExecutorService proxyDns = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
    r -> { Thread t = new Thread(r, "ai-proxy-dns-lookup"); t.setDaemon(true); return t; });

  public AiProviderService(AiConfigStore configs, AiEndpointPolicy endpoints, AiRequestRegistry requests) {
    this.configs = configs; this.endpoints = endpoints; this.requests = requests;
  }
  public int maxInputChars() { return configs.configuration().maxInputChars(); }
  public int maxOutputTokens() { return configs.configuration().maxOutputTokens(); }
  public String model() { return configs.configuration().model(); }

  public record Message(String role, String content) {}
  public void prepareSuggestion(UUID requestId, boolean writing) { requests.prepareSuggestion(requestId, writing); }

  public JsonNode generate(UUID requestId, String systemPrompt, String userPrompt, int maxOutputTokens) {
    return generateConversation(requestId, List.of(new Message("system", Objects.toString(systemPrompt, "")), new Message("user", Objects.toString(userPrompt, ""))), maxOutputTokens);
  }

  public JsonNode generateConversation(UUID requestId, List<Message> conversation, int maxOutputTokens) {
    requests.check(requestId);
    var config = configs.configuration();
    requests.verifyRevision(requestId, config.revision());
    if (conversation == null || conversation.isEmpty() || conversation.size() > 4
      || conversation.stream().anyMatch(message -> message == null || message.content() == null || !Set.of("system", "user", "assistant").contains(message.role()))
      || conversation.stream().mapToLong(message -> message.content().length()).sum() > config.maxInputChars())
      throw new AiProblem(413, "AI_CONTEXT_TOO_LARGE", conversation != null && conversation.size() > 2
        ? "修正所需上下文超过输入预算，未发送第二轮；首轮请求可能已计费，请减少范围或调整预算"
        : "当前资料超过已设置的 AI 上下文预算；没有发送或悄悄截断，请减少范围或调整预算");
    if (maxOutputTokens < 1) throw AiProblem.bad("AI 输出预算无效");
    if (!slots.tryAcquire()) throw new AiProblem(429, "AI_BUSY", "已有两个 AI 请求正在处理，请等待或取消后重试");
    try {
      AiEndpointPolicy.Target target = resolve(requestId, config);
      requests.check(requestId);
      String key = configs.key(config);
      ObjectNode body = JSON.createObjectNode().put("model", config.model()).put("stream", false)
        .put(config.tokenParameter(), Math.min(maxOutputTokens, config.maxOutputTokens()));
      applyStructuredOutputProfile(body, config);
      var messages = body.putArray("messages");
      for (Message message : conversation) messages.addObject().put("role", message.role()).put("content", message.content());
      HttpPost post = new HttpPost(target.uri());
      post.setHeader("Content-Type", "application/json"); post.setHeader("Accept", "application/json");
      if (!key.isEmpty()) post.setHeader("Authorization", "Bearer " + key);
      try { post.setEntity(new ByteArrayEntity(JSON.writeValueAsBytes(body))); }
      catch (IOException invalid) { throw AiProblem.bad("AI 请求无法编码"); }
      requests.attach(requestId, post::abort);
      requests.startCall(requestId);
      AtomicBoolean expired = new AtomicBoolean();
      boolean usageRecorded = false;
      ScheduledFuture<?> deadline = deadlines.schedule(() -> { expired.set(true); post.abort(); }, Math.max(1, Math.min(90000, requests.remainingMillis(requestId))), TimeUnit.MILLISECONDS);
      try (CloseableHttpClient client = client(target); CloseableHttpResponse response = client.execute(post)) {
        requests.check(requestId);
        int status = response.getStatusLine().getStatusCode();
        if (status != 200) throw remoteFailure(status);
        if (response.getEntity() == null) throw invalidOutput();
        byte[] bytes;
        try (InputStream stream = response.getEntity().getContent()) { bytes = stream.readNBytes(1048577); }
        if (bytes.length > 1048576) throw invalidOutput();
        requests.check(requestId);
        if (expired.get()) throw timeout();
        JsonNode envelope;
        try { envelope = JSON.readTree(bytes); } catch (IOException invalid) { throw invalidOutput(); }
        requests.metadata(requestId, config.model(), safeUsage(envelope.path("usage")));
        usageRecorded = true;
        JsonNode result = parseResult(envelope);
        return result;
      } catch (AiProblem problem) {
        requests.check(requestId);
        if (expired.get()) throw timeout();
        throw problem;
      }
      catch (IOException | RuntimeException error) {
        requests.check(requestId);
        if (expired.get() || error instanceof java.net.SocketTimeoutException) throw timeout();
        // Do not copy exception messages, URLs, headers, response bodies or causes into errors/logs.
        throw new AiProblem(502, "AI_CONNECTION_FAILED", "AI 请求未完成，请检查地址、证书和服务状态；为避免重复计费，未自动重试");
      } finally {
        deadline.cancel(false); post.releaseConnection();
        if (!usageRecorded) requests.metadata(requestId, config.model(), null);
      }
    } finally { slots.release(); }
  }

  private AiEndpointPolicy.Target resolve(UUID id, AiConfigStore.Configuration config) {
    Future<AiEndpointPolicy.Target> lookup;
    try { lookup = (config.proxyUrl().isEmpty() ? dns : proxyDns).submit(() -> endpoints.resolve(config.baseUrl(), config.endpointMode(), config.proxyUrl())); }
    catch (RejectedExecutionException busy) { throw new AiProblem(429, "AI_DNS_BUSY", "地址解析正在处理，请稍后重试"); }
    try {
      requests.attach(id, () -> lookup.cancel(true));
      return lookup.get(Math.max(1, Math.min(10000, requests.remainingMillis(id))), TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw timeout(); }
    catch (TimeoutException timedOut) { throw new AiProblem(504, "AI_DNS_TIMEOUT", "服务地址解析超时；若网络需要代理，请在 AI 设置中填写本机代理。没有向模型发送小说资料"); }
    catch (CancellationException cancelled) { requests.check(id); throw timeout(); }
    catch (ExecutionException failed) {
      if (failed.getCause() instanceof AiProblem problem) throw problem;
      throw new AiProblem(502, "AI_DNS_FAILED", "服务地址解析失败，没有向模型发送小说资料");
    } finally { lookup.cancel(true); }
  }

  protected CloseableHttpClient client(AiEndpointPolicy.Target target) {
    var builder = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
      .setDnsResolver(host -> {
        if (!host.replace("[", "").replace("]", "").equalsIgnoreCase(target.uri().getHost().replace("[", "").replace("]", ""))) throw new UnknownHostException("Unapproved AI host");
        return target.addresses();
      })
      .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(10000).setConnectionRequestTimeout(5000).setSocketTimeout(30000).build());
    if (!target.proxyUrl().isEmpty()) builder.setSSLSocketFactory(AiProxyTransport.socketFactory(target.proxyUrl()));
    return builder.build();
  }

  /** The official current DeepSeek models default to thinking, which consumes our bounded JSON budget. */
  static void applyStructuredOutputProfile(ObjectNode body, AiConfigStore.Configuration config) {
    URI endpoint = URI.create(config.baseUrl());
    if (config.endpointMode().equals("remote") && "https".equalsIgnoreCase(endpoint.getScheme())
      && "api.deepseek.com".equalsIgnoreCase(endpoint.getHost()) && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
      && Set.of("", "/v1").contains(Objects.toString(endpoint.getPath(), ""))
      && Set.of("deepseek-flash", "deepseek-v4-pro").contains(config.model())) {
      // Documented by DeepSeek: thinking is enabled by default; JSON Output is
      // explicitly selected with response_format. Other compatible providers
      // retain their minimal request, without guessing extra vendor parameters.
      body.putObject("thinking").put("type", "disabled");
      body.putObject("response_format").put("type", "json_object");
    }
  }

  static JsonNode parseResult(JsonNode envelope) {
    try { return parseCompleteResult(envelope); }
    catch (AiProblem failure) {
      JsonNode choices = envelope == null ? JSON.getNodeFactory().missingNode() : envelope.path("choices");
      // Keep only bounded assistant content, never provider envelopes, reasoning or tool payloads.
      if (choices.isArray() && choices.size() == 1) {
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual() && content.asText().length() <= 32768) failure.correctionContent(content.asText());
      }
      throw failure;
    }
  }

  private static JsonNode parseCompleteResult(JsonNode envelope) {
    if (envelope == null || !envelope.isObject()) throw invalidOutput("模型服务没有返回有效的响应对象");
    JsonNode choices = envelope.path("choices");
    if (!choices.isArray() || choices.size() != 1) throw invalidOutput("模型服务没有返回单一完整结果");
    JsonNode choice = choices.get(0), message = choice.path("message");
    if ("length".equals(choice.path("finish_reason").asText())) {
      boolean reasoning = message.path("reasoning_content").isTextual() && !message.path("reasoning_content").asText().isBlank()
        || envelope.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong(0) > 0;
      throw new AiProblem(422, "AI_OUTPUT_TRUNCATED", "模型输出达到本次 token 上限，结果被截断。"
        + (reasoning ? "模型思考过程也占用了输出预算。" : "")
        + "请缩短本次要求或调整输出预算；没有采用任何内容");
    }
    if (!"stop".equals(choice.path("finish_reason").asText())) throw invalidOutput("模型没有正常完成本次生成");
    if (!message.path("content").isTextual() || message.path("content").asText().isBlank()) throw invalidOutput("模型没有返回可用的最终内容；请检查模型的思考模式和输出预算");
    if (message.hasNonNull("function_call")
      || (message.has("tool_calls") && !message.path("tool_calls").isNull() && (!message.path("tool_calls").isArray() || !message.path("tool_calls").isEmpty())))
      throw invalidOutput("模型返回了本次功能不允许执行的工具调用");
    if (message.hasNonNull("refusal")) throw invalidOutput("模型拒绝了本次生成请求");
    String content = message.path("content").textValue();
    if (content.length() > 32768) throw invalidOutput("模型返回的内容超过允许长度");
    try {
      JsonNode result = JSON.readTree(content);
      if (result == null || !result.isObject()) throw invalidOutput("模型最终内容不是要求的 JSON 对象");
      return result;
    } catch (IOException invalid) { throw invalidOutput("模型最终内容不是完整且唯一的 JSON，可能含代码围栏或重复字段"); }
  }

  private static JsonNode safeUsage(JsonNode usage) {
    if (!usage.isObject()) return null;
    ObjectNode safe = JSON.createObjectNode();
    for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
      JsonNode value = usage.get(key);
      if (value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) safe.set(key, value);
    }
    return safe.isEmpty() ? null : safe;
  }
  private static AiProblem remoteFailure(int status) {
    if (status >= 300 && status < 400) return new AiProblem(502, "AI_REDIRECT_BLOCKED", "AI 服务返回了重定向；请填写最终基础地址，密钥没有转发到新目标");
    if (status == 401 || status == 403) return new AiProblem(502, "AI_AUTH_REJECTED", "AI 服务拒绝了凭据或模型权限，请检查当前配置");
    if (status == 429) return new AiProblem(429, "AI_PROVIDER_LIMIT", "AI 服务额度不足或请求过于频繁；未自动重试");
    if (status == 400 || status == 404 || status == 422) return new AiProblem(502, "AI_PROVIDER_INCOMPATIBLE", "AI 服务未接受当前 OpenAI-compatible 请求，请检查基础地址、模型及所选 token 上限参数；未自动重试");
    return new AiProblem(502, "AI_PROVIDER_FAILED", "AI 服务暂时未能完成请求（HTTP " + status + "）；未自动重试");
  }
  private static AiProblem invalidOutput() { return new AiProblem(422, "AI_INVALID_OUTPUT", "模型结果未通过结构检查，可能发生截断、工具调用或非 JSON 输出；没有采用任何内容"); }
  private static AiProblem invalidOutput(String reason) { return new AiProblem(422, "AI_INVALID_OUTPUT", reason + "；没有采用任何内容"); }
  private static AiProblem timeout() { return new AiProblem(504, "AI_TIMEOUT", "AI 请求超时；服务方可能已计费，为避免重复计费，未自动重试"); }
  @PreDestroy public void shutdown() { deadlines.shutdownNow(); dns.shutdownNow(); proxyDns.shutdownNow(); }
}
