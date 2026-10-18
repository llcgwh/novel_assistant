package com.novelwriting.ai;

import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Resolve once, validate every answer, and give the HTTP client these same pinned answers. */
@Component
public class AiEndpointPolicy {
  public record Target(URI uri, InetAddress[] addresses, String proxyUrl) {
    public Target(URI uri, InetAddress[] addresses) { this(uri, addresses, ""); }
    public Target { addresses = addresses.clone(); }
    @Override public InetAddress[] addresses() { return addresses.clone(); }
  }

  public String normalize(String raw, String mode) {
    if (!Set.of("remote", "local").contains(mode == null ? "" : mode)) throw AiProblem.bad("请选择远程或本机服务模式");
    if (raw == null || raw.length() > 1000 || !raw.equals(raw.trim())) throw AiProblem.bad("Base URL 无效");
    try {
      URI uri = new URI(raw);
      String host = uri.getHost(), scheme = uri.getScheme();
      if (host == null || scheme == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
        throw AiProblem.bad("Base URL 不能包含用户名、密码、查询参数或片段");
      scheme = scheme.toLowerCase(Locale.ROOT); host = host.toLowerCase(Locale.ROOT);
      if ((!scheme.equals("https") && !scheme.equals("http")) || (mode.equals("remote") && !scheme.equals("https")))
        throw AiProblem.bad("远程服务必须使用 HTTPS；本机服务可使用 HTTP");
      if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) throw AiProblem.bad("服务端口无效");
      if (mode.equals("local") && !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host))
        throw AiProblem.bad("本机模式仅支持 localhost、127.0.0.1 或 ::1，指后端所在电脑");
      String path = Objects.toString(uri.getRawPath(), "").replaceAll("/+$", "");
      if (!path.matches("(?:/[A-Za-z0-9._~-]+)*")) throw AiProblem.bad("Base URL 路径无效，不能包含编码字符或空白");
      for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw AiProblem.bad("Base URL 不能包含相对路径");
      if (path.endsWith("/chat/completions") || path.endsWith("/responses")) throw AiProblem.bad("请填写接口基础地址，例如 https://example.com/v1，系统会添加 /chat/completions");
      int port = uri.getPort();
      if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
      return new URI(scheme, null, host, port, path, null, null).toASCIIString();
    } catch (URISyntaxException e) { throw AiProblem.bad("Base URL 格式无效"); }
  }

  public Target resolve(String baseUrl, String mode) {
    URI uri = URI.create(normalize(baseUrl, mode) + "/chat/completions");
    try {
      InetAddress[] addresses = lookup(uri.getHost());
      // TUN-mode VPNs can return benchmark-range synthetic addresses even when
      // the network is healthy. Never connect to those addresses or relax the
      // private-network check: obtain real addresses over pinned, verified TLS.
      if (mode.equals("remote") && Arrays.stream(addresses).anyMatch(AiEndpointPolicy::isSyntheticAddress)) {
        if (addresses.length > 32 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address) && !isSyntheticAddress(address)))
          throw AiProblem.bad("服务地址指向未允许的网络范围；本机模型请明确选择本机模式");
        addresses = lookupPublicDns(uri.getHost());
      }
      validateAddresses(addresses, mode);
      return new Target(uri, addresses);
    } catch (UnknownHostException e) { throw new AiProblem(502, "AI_DNS_FAILED", "无法解析服务地址，请检查 Base URL"); }
  }

  public Target resolve(String baseUrl, String mode, String proxyUrl) {
    String proxy = AiProxyTransport.normalize(proxyUrl);
    if (proxy.isEmpty()) return resolve(baseUrl, mode);
    URI uri = URI.create(normalize(baseUrl, mode) + "/chat/completions");
    if (!mode.equals("remote")) throw AiProblem.bad("本机模型直接连接，无需填写本机代理");
    InetAddress[] addresses = lookupViaProxy(uri.getHost(), proxy);
    validateAddresses(addresses, mode);
    return new Target(uri, addresses, proxy);
  }

  protected InetAddress[] lookupViaProxy(String host, String proxyUrl) { return AiProxyTransport.resolve(host, proxyUrl); }
  protected InetAddress[] lookupPublicDns(String host) { return AiProxyTransport.resolveDirect(host); }

  private static boolean isSyntheticAddress(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 4 && (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
  }

  private static void validateAddresses(InetAddress[] addresses, String mode) {
    if (addresses.length == 0 || addresses.length > 32) throw AiProblem.bad("服务地址没有可用的网络目标");
    for (InetAddress address : addresses) {
      if (mode.equals("local") ? !address.isLoopbackAddress() : !isPublic(address))
        throw AiProblem.bad("服务地址指向未允许的网络范围；本机模型请明确选择本机模式");
    }
  }

  protected InetAddress[] lookup(String host) throws UnknownHostException { return InetAddress.getAllByName(host); }

  static boolean isPublic(InetAddress value) {
    if (value.isAnyLocalAddress() || value.isLoopbackAddress() || value.isLinkLocalAddress() || value.isSiteLocalAddress() || value.isMulticastAddress()) return false;
    byte[] bytes = value.getAddress();
    int a = bytes[0] & 255, b = bytes[1] & 255;
    if (bytes.length == 4) {
      int c = bytes[2] & 255;
      return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
        || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
        || (a == 192 && b == 0 && (c == 0 || c == 2)) || (a == 192 && b == 88 && c == 99)
        || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) || (a == 203 && b == 0 && c == 113));
    }
    // Only ordinary global unicast IPv6; exclude transition/tunnel and documentation ranges.
    if (bytes.length != 16 || (a & 0xe0) != 0x20) return false;
    if (a == 0x20 && b == 0x02) return false; // 6to4 can encapsulate a forbidden IPv4 address.
    if (a == 0x20 && b == 0x01 && ((bytes[2] & 255) < 2 || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8))) return false;
    return true;
  }
}
