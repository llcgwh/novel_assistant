package com.novelwriting.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.security.CredentialCipher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Device-local AI configuration, deliberately outside every novel export and WebDAV bundle. */
@Service
public class AiConfigStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> INPUT_FIELDS = Set.of("baseUrl", "model", "endpointMode", "apiKey", "clearKey", "expectedRevision", "maxInputChars", "maxOutputTokens", "tokenParameter", "proxyUrl");
  private final Path configFile;
  private final CredentialCipher cipher;
  private final AiEndpointPolicy policy;
  private Saved cached;

  private record Saved(String revision, String baseUrl, String model, String endpointMode, String encryptedKey, int maxInputChars, int maxOutputTokens, String tokenParameter, String proxyUrl) {}
  public record Configuration(String revision, String baseUrl, String model, String endpointMode, int maxInputChars, int maxOutputTokens, String tokenParameter, String proxyUrl) {}

  public AiConfigStore(@Value("${NOVEL_AI_CONFIG_FILE:.secrets/ai-config.json}") String configFile,
                      @Value("${NOVEL_AI_KEY_FILE:.secrets/ai.key}") String keyFile, AiEndpointPolicy policy) {
    this.configFile = Path.of(configFile).toAbsolutePath().normalize();
    // This is an independent key, never the WebDAV key/environment setting or rotation target.
    this.cipher = new CredentialCipher("", keyFile);
    this.policy = policy;
    if (this.configFile.equals(Path.of(keyFile).toAbsolutePath().normalize())) throw new IllegalArgumentException("AI configuration and credential key paths must differ");
  }

  public synchronized ObjectNode status() {
    Saved s = read();
    return JSON.createObjectNode().put("revision", s.revision()).put("baseUrl", s.baseUrl()).put("model", s.model())
      .put("endpointMode", s.endpointMode()).put("hasKey", !s.encryptedKey().isEmpty())
      .put("configured", !s.baseUrl().isEmpty() && !s.model().isEmpty() && (s.endpointMode().equals("local") || !s.encryptedKey().isEmpty()))
      .put("maxInputChars", s.maxInputChars()).put("maxOutputTokens", s.maxOutputTokens()).put("tokenParameter", s.tokenParameter()).put("proxyUrl", s.proxyUrl());
  }

  public synchronized ObjectNode update(JsonNode input) {
    exact(input, INPUT_FIELDS);
    Saved before = read();
    if (!text(input, "expectedRevision", 64).equals(before.revision())) throw new AiProblem(409, "AI_CONFIG_CHANGED", "AI 配置已变化，请重新读取后保存");
    String mode = text(input, "endpointMode", 16), endpoint = policy.normalize(text(input, "baseUrl", 1000), mode);
    String proxyUrl = proxyUrl(input, mode, before.proxyUrl());
    String model = text(input, "model", 200);
    if (model.isBlank() || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}")) throw AiProblem.bad("模型名称无效，请填写服务方提供的模型 ID");
    if (input.has("clearKey") && !input.get("clearKey").isBoolean()) throw AiProblem.bad("clearKey 必须为布尔值");
    boolean clear = input.path("clearKey").asBoolean(false);
    String provided = input.has("apiKey") ? text(input, "apiKey", 4096) : "";
    if (provided.chars().anyMatch(c -> c < 33 || c > 126)) throw AiProblem.bad("API key 不能包含空白或控制字符");
    if (clear && !provided.isEmpty()) throw AiProblem.bad("不能同时删除和替换密钥");
    boolean same = before.baseUrl().equals(endpoint) && before.endpointMode().equals(mode);
    String encrypted = before.encryptedKey();
    if (!same || clear) encrypted = "";
    if (!provided.isEmpty()) encrypted = cipher.encrypt(binding(endpoint, mode) + provided);
    if (mode.equals("remote") && encrypted.isEmpty() && !clear)
      throw AiProblem.bad("新地址需要对应的 API key；旧密钥不会发送到改变后的服务地址");
    int inputLimit = number(input, "maxInputChars", before.maxInputChars(), 1000, 180000);
    int outputLimit = number(input, "maxOutputTokens", before.maxOutputTokens(), 128, 4096);
    String tokenParameter = tokenParameter(input, before.tokenParameter());
    Saved next = new Saved(UUID.randomUUID().toString(), endpoint, model, mode, encrypted, inputLimit, outputLimit, tokenParameter, proxyUrl);
    persist(next); cached = next;
    return status();
  }

  public synchronized Configuration configuration() {
    Saved s = read();
    if (s.baseUrl().isEmpty() || s.model().isEmpty() || (s.endpointMode().equals("remote") && s.encryptedKey().isEmpty()))
      throw new AiProblem(409, "AI_NOT_CONFIGURED", "请先在设置页保存 AI 服务地址、模型和对应密钥");
    return new Configuration(s.revision(), s.baseUrl(), s.model(), s.endpointMode(), s.maxInputChars(), s.maxOutputTokens(), s.tokenParameter(), s.proxyUrl());
  }

  synchronized String key(Configuration snapshot) {
    Saved s = read();
    if (!s.revision().equals(snapshot.revision())) throw new AiProblem(409, "AI_CONFIG_CHANGED", "AI 配置已变化，请重新发起请求");
    if (s.encryptedKey().isEmpty()) return "";
    try {
      if (!s.encryptedKey().startsWith(CredentialCipher.PREFIX)) throw new IllegalStateException();
      String decoded = cipher.decrypt(s.encryptedKey()), prefix = binding(s.baseUrl(), s.endpointMode());
      if (!decoded.startsWith(prefix)) throw new IllegalStateException();
      return decoded.substring(prefix.length());
    } catch (RuntimeException e) { throw new AiProblem(409, "AI_KEY_UNAVAILABLE", "AI 密钥无法读取，请重新填写；请保留原本机密钥文件"); }
  }

  private static String binding(String endpoint, String mode) { return "ai-key-v1\n" + mode + "\n" + endpoint + "\n"; }

  private Saved read() {
    if (cached != null) return cached;
    if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) return cached = new Saved("0", "", "", "remote", "", 120000, 2000, "max_tokens", "");
    try {
      if (Files.isSymbolicLink(configFile) || Files.size(configFile) > 16384) throw new IOException();
      JsonNode n = JSON.readTree(Files.readAllBytes(configFile));
      exact(n, Set.of("schemaVersion", "revision", "baseUrl", "model", "endpointMode", "encryptedKey", "maxInputChars", "maxOutputTokens", "tokenParameter", "proxyUrl"));
      if (n.path("schemaVersion").asInt() != 1) throw new IOException();
      String mode = text(n, "endpointMode", 16), endpoint = policy.normalize(text(n, "baseUrl", 1000), mode);
      String encrypted = text(n, "encryptedKey", 10000);
      if (!encrypted.isEmpty() && !encrypted.startsWith(CredentialCipher.PREFIX)) throw new IOException();
      String revision = text(n, "revision", 64); UUID.fromString(revision);
      return cached = new Saved(revision, endpoint, text(n, "model", 200), mode, encrypted,
        number(n, "maxInputChars", 120000, 1000, 180000), number(n, "maxOutputTokens", 2000, 128, 4096), tokenParameter(n, "max_tokens"), proxyUrl(n, mode, ""));
    } catch (Exception e) { throw new AiProblem(409, "AI_CONFIG_UNAVAILABLE", "AI 本机配置无法读取，请检查配置文件及其权限"); }
  }

  private void persist(Saved value) {
    Path temporary = null;
    try {
      if (Files.isSymbolicLink(configFile) || Files.isSymbolicLink(configFile.getParent())) throw new IOException();
      boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
      FileAttribute<?>[] dirMode = posix ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))} : new FileAttribute<?>[0];
      FileAttribute<?>[] fileMode = posix ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))} : new FileAttribute<?>[0];
      Files.createDirectories(configFile.getParent(), dirMode);
      temporary = Files.createTempFile(configFile.getParent(), ".ai-config-", ".tmp", fileMode);
      ObjectNode n = JSON.createObjectNode().put("schemaVersion", 1).put("revision", value.revision()).put("baseUrl", value.baseUrl())
        .put("model", value.model()).put("endpointMode", value.endpointMode()).put("encryptedKey", value.encryptedKey())
        .put("maxInputChars", value.maxInputChars()).put("maxOutputTokens", value.maxOutputTokens()).put("tokenParameter", value.tokenParameter()).put("proxyUrl", value.proxyUrl());
      Files.writeString(temporary, JSON.writeValueAsString(n), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) { throw new AiProblem(500, "AI_CONFIG_SAVE_FAILED", "AI 配置保存失败，原配置仍保留；请检查本机文件权限"); }
    finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
  }

  static void exact(JsonNode input, Set<String> fields) {
    if (input == null || !input.isObject()) throw AiProblem.bad("请求必须是 JSON 对象");
    input.fieldNames().forEachRemaining(key -> { if (!fields.contains(key)) throw AiProblem.bad("请求包含不支持的字段"); });
  }
  static String text(JsonNode input, String key, int max) {
    JsonNode v = input.get(key);
    if (v == null || !v.isTextual() || v.textValue().length() > max) throw AiProblem.bad("请求字段无效：" + key);
    return v.textValue();
  }
  static int number(JsonNode input, String key, int fallback, int min, int max) {
    JsonNode v = input.get(key);
    if (v == null) return fallback;
    if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max) throw AiProblem.bad("请求预算超出允许范围：" + key);
    return v.intValue();
  }
  private static String tokenParameter(JsonNode input, String fallback) {
    String value = input.has("tokenParameter") ? text(input, "tokenParameter", 32) : fallback;
    if (!Set.of("max_tokens", "max_completion_tokens").contains(value)) throw AiProblem.bad("不支持的模型 token 上限参数");
    return value;
  }
  private static String proxyUrl(JsonNode input, String mode, String fallback) {
    String value = AiProxyTransport.normalize(input.has("proxyUrl") ? text(input, "proxyUrl", 200) : (mode.equals("local") ? "" : fallback));
    if (mode.equals("local") && !value.isEmpty()) throw AiProblem.bad("本机模型直接连接，无需填写本机代理");
    return value;
  }
}
