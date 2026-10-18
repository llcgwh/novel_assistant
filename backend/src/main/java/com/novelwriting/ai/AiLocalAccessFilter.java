package com.novelwriting.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protect only AI routes, independently of the legacy application's permissive CORS. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiLocalAccessFilter extends OncePerRequestFilter {
  public static final String SESSION_ATTRIBUTE = AiLocalAccessFilter.class.getName() + ".session";
  private static final ObjectMapper JSON = new ObjectMapper();
  private record Session(String origin, Instant expires) {}
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Set<String> origins;
  private final SecureRandom random = new SecureRandom();

  public AiLocalAccessFilter(@Value("${NOVEL_AI_ALLOWED_ORIGINS:http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080,http://127.0.0.1:8080}") String allowed) {
    Set<String> values = new HashSet<>();
    for (String raw : allowed.split(",")) {
      String value = raw.trim();
      try {
        URI uri = URI.create(value);
        if (!localName(uri.getHost()) || !Set.of("http", "https").contains(uri.getScheme()) || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException();
      } catch (RuntimeException invalid) { throw new IllegalArgumentException("AI allowed origins must be explicit local HTTP origins"); }
      values.add(value);
    }
    origins = Set.copyOf(values);
  }

  private static boolean localName(String host) { return host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT)); }
  private static boolean localPeer(String address) {
    // No DNS lookup for an untrusted request header or hostname.
    return address != null && (address.equals("127.0.0.1") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"));
  }
  private static String path(HttpServletRequest request) {
    // Containers can route a normalized servlet path while retaining dot segments in
    // requestURI. Guard the path that will actually be routed, including test/mock paths.
    String path = request.getServletPath();
    if (path == null || path.isEmpty()) path = request.getRequestURI();
    try { path = URLDecoder.decode(path, StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) {}
    path = path.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
    Deque<String> parts = new ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
      else parts.addLast(part);
    }
    return "/" + String.join("/", parts);
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = path(request);
    return !(path.equals("/api/ai") || path.startsWith("/api/ai/") || path.matches("/api/novels/[^/]+/ai(?:/.*)?"));
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    String origin = request.getHeader("Origin");
    // Same-origin GET requests can omit Origin. Vite's local /api proxy changes Host
    // to the backend, so derive the browser origin from its Referer, not the proxy Host.
    // Fetch Metadata must say same-origin, and the resulting origin must still be an
    // explicit local allowlist entry. Never trust Forwarded/X-Forwarded-* headers.
    if (origin == null && "same-origin".equals(request.getHeader("Sec-Fetch-Site"))) {
      try {
        URI referer = URI.create(Objects.toString(request.getHeader("Referer"), ""));
        origin = new URI(referer.getScheme(), null, referer.getHost(), referer.getPort(), null, null, null).toString();
      } catch (Exception invalid) { origin = null; }
    }
    if (!localPeer(request.getRemoteAddr()) || !localName(request.getServerName()) || origin == null || !origins.contains(origin)) {
      reject(response, 403, "AI_LOCAL_ACCESS_ONLY", "AI 服务仅允许本机受信任的创作界面访问"); return;
    }
    response.setHeader("Access-Control-Allow-Origin", origin);
    response.setHeader("Vary", "Origin");
    if (request.getMethod().equals("OPTIONS")) {
      response.setHeader("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS");
      response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-AI-Session");
      response.setHeader("Access-Control-Max-Age", "600");
      response.setStatus(204); return;
    }
    if (request.getMethod().equals("GET") && path(request).equals("/api/ai/session")) {
      sessions.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(Instant.now()));
      if (sessions.size() >= 64) { reject(response, 429, "AI_SESSION_LIMIT", "打开的 AI 会话过多，请关闭旧页面后重试"); return; }
      byte[] bytes = new byte[32]; random.nextBytes(bytes);
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      sessions.put(token, new Session(origin, Instant.now().plus(Duration.ofHours(8))));
      response.setContentType("application/json;charset=UTF-8");
      JSON.writeValue(response.getOutputStream(), Map.of("token", token)); return;
    }
    String token = request.getHeader("X-AI-Session");
    Session session = token == null ? null : sessions.get(token);
    if (session == null || !session.origin().equals(origin) || !session.expires().isAfter(Instant.now())) {
      reject(response, 401, "AI_SESSION_REQUIRED", "AI 会话已失效，请重新连接本机服务"); return;
    }
    request.setAttribute(SESSION_ATTRIBUTE, token);
    if (Set.of("PUT", "POST").contains(request.getMethod())) {
      int limit = path(request).equals("/api/ai/config") ? 16384 : 1048576;
      if (request.getContentLengthLong() > limit) { reject(response, 413, "AI_REQUEST_TOO_LARGE", "AI 请求过大，请缩小当前草稿范围"); return; }
      byte[] bytes = request.getInputStream().readNBytes(limit + 1);
      if (bytes.length > limit) { reject(response, 413, "AI_REQUEST_TOO_LARGE", "AI 请求过大，请缩小当前草稿范围"); return; }
      chain.doFilter(new HttpServletRequestWrapper(request) {
        @Override public ServletInputStream getInputStream() {
          ByteArrayInputStream source = new ByteArrayInputStream(bytes);
          return new ServletInputStream() {
            @Override public int read() { return source.read(); }
            @Override public int read(byte[] b, int off, int len) { return source.read(b, off, len); }
            @Override public boolean isFinished() { return source.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
          };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
      }, response);
    } else chain.doFilter(request, response);
  }

  private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
    JSON.writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
  }
}
