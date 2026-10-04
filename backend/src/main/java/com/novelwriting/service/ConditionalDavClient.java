package com.novelwriting.service;

import com.github.sardine.impl.SardineImpl;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.handler.VoidResponseHandler;
import com.github.sardine.impl.methods.HttpLock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.config.RequestConfig;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.helpers.DefaultHandler;

/** Conditional writes and bounded, verified collection locks for destructive maintenance. */
class ConditionalDavClient extends SardineImpl {
  static final int LOCK_SECONDS = 120;
  private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
    .setConnectTimeout(10000).setConnectionRequestTimeout(10000).setSocketTimeout(45000).build();
  private static final String DAV = "DAV:";
  private static final String LOCK_BODY = """
    <d:lockinfo xmlns:d="DAV:"><d:lockscope><d:exclusive/></d:lockscope>
    <d:locktype><d:write/></d:locktype></d:lockinfo>
    """;

  ConditionalDavClient(String username, String password) {
    super(HttpClients.custom().disableAutomaticRetries().setDefaultRequestConfig(REQUEST_CONFIG), username, password);
  }

  record HeadMetadata(String etag, long size) {}

  /** Read the HTTP validator itself; never turn a PROPFIND opaque value into deletion authority. */
  HeadMetadata headMetadata(String url) throws IOException {
    HttpHead request = new HttpHead(url);
    request.setConfig(RequestConfig.copy(REQUEST_CONFIG).setRedirectsEnabled(false).build());
    request.setHeader("Accept-Encoding", "identity");
    request.setHeader("Cache-Control", "no-cache");
    return execute(request, response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status != 200) throw new SardineException("Unexpected HEAD response", status, "Metadata check failed");
      String etag = null;
      var validators = response.getHeaders("ETag");
      if (validators.length == 1) {
        String value = validators[0].getValue().trim();
        // Some DAV servers strip quotes before applying If-Match and then
        // mistake the opaque value "*" for the wildcard condition.
        if (value.length() <= 4096 && !"\"*\"".equals(value) && value.matches("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"")) etag = value;
      }
      long size = -1;
      var lengths = response.getHeaders("Content-Length");
      if (lengths.length == 1) {
        String value = lengths[0].getValue().trim();
        if (value.matches("[0-9]{1,19}")) {
          try { size = Long.parseLong(value); }
          catch (NumberFormatException tooLarge) { /* Unknown size cannot authorize deletion. */ }
        }
      }
      var encoding = response.getHeaders("Content-Encoding");
      if (encoding.length > 1 || (encoding.length == 1 && !"identity".equalsIgnoreCase(encoding[0].getValue().trim()))) size = -1;
      return new HeadMetadata(etag, size);
    });
  }

  @Override public String lock(String url) throws IOException {
    HttpLock request = lockRequest(url);
    request.setEntity(new StringEntity(LOCK_BODY, StandardCharsets.UTF_8));
    String[] acquired = new String[1];
    try {
      return execute(request, response -> verifiedToken(response, url, null, acquired));
    } catch (IOException failure) {
      // A refused scope/lease still may have created a lock. Release only the
      // token explicitly returned for this successful LOCK, never a discovered lock.
      if (acquired[0] != null) {
        try { unlock(url, acquired[0]); }
        catch (IOException releaseFailure) {
          failure.addSuppressed(new IOException("无法确认远端目录锁已释放"));
        }
      }
      throw failure;
    }
  }

  @Override public String refreshLock(String url, String token, String resource) throws IOException {
    if (!validToken(token) || !sameResource(url, resource)) throw invalidLock();
    HttpLock request = lockRequest(url);
    request.setHeader("If", "<" + resource + "> (<" + token + ">)");
    return execute(request, response -> verifiedToken(response, url, token, new String[1]));
  }

  private static HttpLock lockRequest(String url) {
    HttpLock request = new HttpLock(url);
    request.setDepth("infinity");
    // Without a finite requested lease, a lost LOCK response can strand the
    // directory indefinitely. A server that grants an unsafe lease is refused.
    request.setTimeout(LOCK_SECONDS);
    return request;
  }

  private static String verifiedToken(HttpResponse response, String url, String expected, String[] acquired)
    throws IOException {
    int status = response.getStatusLine().getStatusCode();
    if (status != 200 && status != 201) throw new SardineException("Unexpected LOCK response", status, "LOCK failed");
    var headers = response.getHeaders("Lock-Token");
    if (headers.length > 1) throw invalidLock();
    String token = headers.length == 1 ? headerToken(headers[0].getValue()) : expected;
    if (token == null || (expected != null && !expected.equals(token))) throw invalidLock();
    if (expected == null) acquired[0] = token;
    if (response.getEntity() == null) throw invalidLock();
    Element root;
    try (var input = response.getEntity().getContent()) {
      byte[] xml = BackupBundleService.readLimited(input, 64 * 1024);
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
    } catch (Exception malformed) { throw invalidLock(); }
    if (!DAV.equals(root.getNamespaceURI()) || !"prop".equals(root.getLocalName())) throw invalidLock();
    Element lock = child(child(root, "lockdiscovery"), "activelock");
    Element scope = child(lock, "lockscope"), type = child(lock, "locktype");
    if (elements(scope).size() != 1 || elements(type).size() != 1) throw invalidLock();
    child(scope, "exclusive"); child(type, "write");
    if (!"infinity".equals(simpleText(child(lock, "depth")))) throw invalidLock();
    String timeout = simpleText(child(lock, "timeout"));
    if (!timeout.matches("Second-[0-9]{1,9}")) throw invalidLock();
    int seconds = Integer.parseInt(timeout.substring(7));
    if (seconds <= 0 || seconds > LOCK_SECONDS) throw invalidLock();
    if (!token.equals(simpleText(child(child(lock, "locktoken"), "href")))) throw invalidLock();
    if (!sameResource(url, simpleText(child(child(lock, "lockroot"), "href")))) throw invalidLock();
    return token;
  }

  private static boolean sameResource(String expected, String actual) {
    try {
      URI base = URI.create(expected).normalize();
      URI resolved = base.resolve(actual).normalize();
      return base.equals(resolved);
    } catch (IllegalArgumentException invalid) { return false; }
  }

  private static String headerToken(String value) throws IOException {
    String trimmed = value.trim();
    // WsgiDAV returns a bare absolute URI here. Accept that representation as
    // well as the standard <URI>; XML identity, scope, root and lease are still verified.
    String token = trimmed.startsWith("<") && trimmed.endsWith(">")
      ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    if (!validToken(token)) throw invalidLock();
    return token;
  }

  private static boolean validToken(String token) {
    if (token == null || token.length() > 2048 || token.chars().anyMatch(c -> c <= 32 || c >= 127)) return false;
    try { return URI.create(token).isAbsolute(); }
    catch (IllegalArgumentException invalid) { return false; }
  }

  private static List<Element> elements(Element parent) {
    List<Element> children = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element) children.add(element);
    return children;
  }

  private static Element child(Element parent, String name) throws IOException {
    List<Element> matches = elements(parent).stream()
      .filter(e -> DAV.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())).toList();
    if (matches.size() != 1) throw invalidLock();
    return matches.get(0);
  }

  private static String simpleText(Element element) throws IOException {
    if (!elements(element).isEmpty()) throw invalidLock();
    return element.getTextContent().trim();
  }

  private static IOException invalidLock() {
    return new IOException("远端未提供可验证的目录排他锁和有限租期，清理已停止");
  }

  @Override public void put(String url, InputStream data, Map<String, String> headers) throws IOException {
    byte[] bytes;
    try (data) {
      bytes = BackupBundleService.readLimited(data, BackupBundleService.MAX_BACKUP_BYTES + 1024 * 1024);
    }
    // Sardine's stream overload is non-repeatable. A normal 401 authentication
    // challenge must be able to resend this bounded body with its conditions.
    HttpPut request = new HttpPut(url);
    request.setEntity(new ByteArrayEntity(bytes));
    headers.forEach(request::addHeader);
    if (!request.containsHeader("Content-Type")) request.setHeader("Content-Type", "application/octet-stream");
    execute(request, new VoidResponseHandler());
  }

  @Override public void delete(String url, Map<String, String> headers) throws IOException {
    // Sardine 5.13's implementation silently ignores this headers argument.
    HttpDelete request = new HttpDelete(url);
    headers.forEach(request::addHeader);
    execute(request, new VoidResponseHandler());
  }
}
