package com.novelwriting.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.util.InetAddressUtils;
import org.apache.http.impl.client.*;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;

/** Pinned encrypted DNS and explicit loopback proxy transport, retaining normal TLS verification. */
public final class AiProxyTransport {
  private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final int CONNECT_TIMEOUT = 4000, READ_TIMEOUT = 5000, DNS_LIMIT = 65536, HEADER_LIMIT = 8192;
  private AiProxyTransport() {}

  public static String normalize(String raw) {
    if (raw == null || raw.isEmpty()) return "";
    if (raw.length() > 200 || !raw.equals(raw.trim())) throw invalidProxy();
    try {
      URI uri = new URI(raw);
      String host = uri.getHost();
      if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null || uri.getRawFragment() != null || !Objects.toString(uri.getRawPath(), "").isEmpty()
        || uri.getPort() < 1 || uri.getPort() > 65535) throw invalidProxy();
      InetAddress address = literal(host);
      if (address == null || !address.isLoopbackAddress()) throw invalidProxy();
      return new URI("http", null, address.getHostAddress(), uri.getPort(), null, null, null).toASCIIString();
    } catch (URISyntaxException invalid) { throw invalidProxy(); }
  }

  /** Sends only the requested hostname to Google Public DNS over a pinned TLS tunnel; never sends model credentials. */
  public static InetAddress[] resolve(String host, String proxyUrl) {
    return resolve(host, proxyUrl, SSLContexts.createDefault(), 8000);
  }

  static InetAddress[] resolve(String host, String proxyUrl, SSLContext tls, long deadlineMillis) {
    String proxy = normalize(proxyUrl);
    if (proxy.isEmpty()) throw invalidProxy();
    return encryptedDns(host, socketFactory(proxy, tls), deadlineMillis, true);
  }

  /** Recover real public addresses from VPN synthetic DNS without changing the chosen connection mode. */
  public static InetAddress[] resolveDirect(String host) {
    return encryptedDns(host, new SSLConnectionSocketFactory(SSLContexts.createDefault(),
      SSLConnectionSocketFactory.getDefaultHostnameVerifier()), 8000, false);
  }

  static InetAddress[] encryptedDns(String host, SSLConnectionSocketFactory sockets, long deadlineMillis, boolean viaProxy) {
    InetAddress numeric = literal(host);
    if (numeric != null) return new InetAddress[]{numeric};
    if (host == null || host.length() > 253 || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.?")) throw dnsFailure();
    for (String label : host.replaceFirst("\\.$", "").split("\\.", -1))
      if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) throw dnsFailure();
    checkInterrupted();
    HttpGet query = new HttpGet("https://dns.google/resolve?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8)
      + "&type=A&edns_client_subnet=0.0.0.0%2F0");
    query.setHeader("Accept", "application/dns-json");
    Thread caller = Thread.currentThread();
    AtomicBoolean expired = new AtomicBoolean();
    ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(work -> {
      Thread thread = new Thread(work, "ai-encrypted-dns-deadline"); thread.setDaemon(true); return thread;
    });
    deadline.schedule(() -> { expired.set(true); query.abort(); }, deadlineMillis, TimeUnit.MILLISECONDS);
    // Future.cancel interrupts the DNS worker; abort closes a currently blocked socket as well.
    deadline.scheduleAtFixedRate(() -> { if (caller.isInterrupted()) query.abort(); }, 0, 50, TimeUnit.MILLISECONDS);
    try (CloseableHttpClient client = HttpClients.custom()
      .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableContentCompression()
      .setSSLSocketFactory(sockets)
      .setDnsResolver(name -> {
        if (!name.equals("dns.google")) throw new UnknownHostException();
        return new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})};
      })
      .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(CONNECT_TIMEOUT).setConnectionRequestTimeout(1000)
        .setSocketTimeout(READ_TIMEOUT).build()).build();
      var response = client.execute(query)) {
      checkInterrupted();
      if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) throw dnsFailure();
      byte[] body;
      try (InputStream input = response.getEntity().getContent()) { body = input.readNBytes(DNS_LIMIT + 1); }
      checkInterrupted();
      if (expired.get()) throw dnsTimeout();
      return parseDnsResponse(body, host);
    } catch (AiProblem problem) {
      checkInterrupted();
      if (!viaProxy) throw publicDnsFailure(expired.get());
      if (expired.get()) throw dnsTimeout();
      throw problem;
    }
    catch (IOException | RuntimeException failure) {
      checkInterrupted();
      if (!viaProxy) throw publicDnsFailure(expired.get());
      if (expired.get()) throw dnsTimeout();
      throw dnsFailure();
    }
    finally { query.abort(); query.releaseConnection(); deadline.shutdownNow(); }
  }

  static InetAddress[] parseDnsResponse(byte[] body, String host) {
    if (body == null || body.length > DNS_LIMIT) throw dnsFailure();
    try {
      JsonNode reply = JSON.readTree(body);
      if (reply == null || !reply.isObject() || !reply.path("Status").isIntegralNumber() || !reply.path("Status").canConvertToInt() || reply.path("Status").intValue() != 0
        || !reply.path("TC").isBoolean() || reply.path("TC").booleanValue()) throw dnsFailure();
      JsonNode question = reply.path("Question"), answers = reply.path("Answer");
      if (!question.isArray() || question.size() != 1 || !question.get(0).path("type").isIntegralNumber() || !question.get(0).path("type").canConvertToInt()
        || question.get(0).path("type").intValue() != 1
        || !dnsName(question.get(0).path("name").asText()).equals(dnsName(host))
        || !answers.isArray() || answers.isEmpty() || answers.size() > 64) throw dnsFailure();
      LinkedHashSet<InetAddress> addresses = new LinkedHashSet<>();
      for (JsonNode answer : answers) {
        if (!answer.path("type").isIntegralNumber() || !answer.path("type").canConvertToInt()) throw dnsFailure();
        if (answer.path("type").intValue() != 1) continue;
        if (!answer.path("data").isTextual() || !InetAddressUtils.isIPv4Address(answer.path("data").textValue())) throw dnsFailure();
        InetAddress address = literal(answer.path("data").textValue());
        if (address == null) throw dnsFailure();
        addresses.add(address);
        if (addresses.size() > 32) throw dnsFailure();
      }
      if (addresses.isEmpty()) throw dnsFailure();
      return addresses.toArray(InetAddress[]::new);
    } catch (IOException invalid) { throw dnsFailure(); }
  }

  public static SSLConnectionSocketFactory socketFactory(String proxyUrl) {
    return socketFactory(proxyUrl, SSLContexts.createDefault());
  }

  static SSLConnectionSocketFactory socketFactory(String proxyUrl, SSLContext tls) {
    String normalized = normalize(proxyUrl);
    if (normalized.isEmpty()) throw invalidProxy();
    URI proxy = URI.create(normalized);
    InetSocketAddress proxyAddress = new InetSocketAddress(Objects.requireNonNull(literal(proxy.getHost())), proxy.getPort());
    return new SSLConnectionSocketFactory(tls, SSLConnectionSocketFactory.getDefaultHostnameVerifier()) {
      @Override public Socket connectSocket(int timeout, Socket supplied, HttpHost host, InetSocketAddress remote,
                                            InetSocketAddress local, HttpContext context) throws IOException {
        if (remote == null || remote.isUnresolved()) throw new IOException("Pinned address required");
        Socket socket = supplied != null ? supplied : createSocket(context);
        boolean established = false;
        try {
          checkInterrupted();
          if (local != null) socket.bind(local);
          try {
            socket.setSoTimeout(READ_TIMEOUT);
            socket.connect(proxyAddress, timeout > 0 ? Math.min(timeout, CONNECT_TIMEOUT) : CONNECT_TIMEOUT);
            String address = remote.getAddress().getHostAddress();
            String authority = (address.contains(":") ? "[" + address + "]" : address) + ":" + remote.getPort();
            // This pre-TLS request deliberately has no model headers or body.
            socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            readTunnelResponse(socket.getInputStream());
          } catch (IOException failure) { throw proxyFailure(); }
          checkInterrupted();
          // The tunnel destination is numeric, while SNI and the default certificate verifier use the original name.
          Socket secure = createLayeredSocket(socket, host.getHostName(), remote.getPort(), context);
          established = true;
          return secure;
        } finally { if (!established) try { socket.close(); } catch (IOException ignored) {} }
      }
    };
  }

  private static void readTunnelResponse(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int matched = 0;
    byte[] ending = {'\r', '\n', '\r', '\n'};
    while (bytes.size() < HEADER_LIMIT) {
      checkInterrupted();
      int next = input.read();
      if (next < 0) throw new EOFException();
      bytes.write(next);
      matched = next == ending[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
      if (matched == ending.length) {
        String header = bytes.toString(StandardCharsets.US_ASCII);
        String status = header.substring(0, header.indexOf("\r\n"));
        if (!status.matches("HTTP/1\\.[01] 200(?: [\\x20-\\x7E]*)?")) throw new IOException("Proxy tunnel rejected");
        return;
      }
    }
    throw new IOException("Proxy header too large");
  }

  private static InetAddress literal(String value) {
    if (value == null) return null;
    String host = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
    if (host.contains("%") || (!InetAddressUtils.isIPv4Address(host) && !InetAddressUtils.isIPv6Address(host))) return null;
    try { return InetAddress.getByName(host); } // Literal syntax was checked above: this never resolves a hostname.
    catch (UnknownHostException invalid) { return null; }
  }
  private static String dnsName(String value) { return value.toLowerCase(Locale.ROOT).replaceFirst("\\.$", ""); }
  private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new AiProblem(409, "AI_CANCELLED", "AI 地址解析已取消，没有继续发送小说资料"); }
  private static AiProblem invalidProxy() { return AiProblem.bad("代理地址仅支持 http://数值本机地址:端口，例如 http://127.0.0.1:7897，不能包含认证信息或路径"); }
  private static AiProblem proxyFailure() { return new AiProblem(502, "AI_PROXY_CONNECTION_FAILED", "本机代理连接失败，请检查代理地址和运行状态；代理程序已关闭时可在 AI 设置切回直连。未改用直连或自动重试"); }
  private static AiProblem dnsTimeout() { return new AiProblem(504, "AI_PROXY_DNS_TIMEOUT", "代理域名解析超时，请检查本机代理；没有向模型发送小说资料"); }
  private static AiProblem dnsFailure() { return new AiProblem(502, "AI_PROXY_DNS_FAILED", "代理域名解析失败，请检查本机代理和服务地址；没有向模型发送小说资料"); }
  private static AiProblem publicDnsFailure(boolean timedOut) {
    return new AiProblem(timedOut ? 504 : 502, timedOut ? "AI_PUBLIC_DNS_TIMEOUT" : "AI_PUBLIC_DNS_FAILED",
      "网络返回了 VPN 的虚拟地址，但公网地址解析" + (timedOut ? "超时" : "失败")
        + "；请检查 VPN，或在 AI 设置中选择本机代理。没有向模型发送小说资料");
  }
}
