package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.github.sardine.*;
import com.novelwriting.entity.*;
import jakarta.persistence.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, expiring cleanup plans. There is deliberately no automatic deletion job. */
@Service
public class BackupMaintenanceService {
  @PersistenceContext private EntityManager em;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private WritingService writing;
  @Autowired private WritingCloudService cloud;
  @Autowired private BackupBundleService bundles;
  @Autowired private BackupOperationService operations;
  private static final Pattern EDGE = Pattern.compile("^([a-f0-9-]{36})_([a-f0-9-]{36})\\.resolved$");
  record Entry(String file, long size, String etag, String modified, boolean directory) {}
  private <T> T tx(java.util.function.Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
  static ResponseStatusException stale(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

  private BackupRetention policy(Long id) {
    writing.book(id); // Serializes policy creation and updates on the parent novel.
    BackupRetention row = em.find(BackupRetention.class, id);
    if (row == null) { row = new BackupRetention(); row.setNovelId(id); em.persist(row); }
    return row;
  }
  private static ObjectNode policyJson(BackupRetention row) {
    return JSON.createObjectNode().put("keepLast", row.getKeepLast()).put("keepDays", row.getKeepDays())
      .put("version", row.getVersion()).put("automatic", false);
  }
  public ObjectNode retention(Long id) { return tx(() -> policyJson(policy(id))); }
  public ObjectNode retention(Long id, JsonNode body) {
    for (String field : List.of("keepLast", "keepDays", "version"))
      if (!body.path(field).isIntegralNumber() || !body.path(field).canConvertToLong()) throw WritingService.bad("保留策略必须提供整数及当前版本");
    long count = body.path("keepLast").asLong(), days = body.path("keepDays").asLong();
    if (count < 2 || count > 5000 || days < 1 || days > 36500) throw WritingService.bad("至少保留2份及1天；上限为5000份、36500天");
    return tx(() -> {
      BackupRetention row = policy(id);
      if (row.getVersion() != body.path("version").asLong()) throw stale("保留策略已变更，请重新载入后确认");
      row.setKeepLast((int) count); row.setKeepDays((int) days); row.setVersion(row.getVersion() + 1);
      return policyJson(row);
    });
  }

  private List<Entry> inventory(Sardine dav, String dir) throws IOException {
    if (!dav.exists(dir)) return List.of();
    String path = URI.create(dir).getPath();
    List<Entry> rows = new ArrayList<>(); Set<String> names = new HashSet<>();
    for (DavResource r : dav.list(dir)) {
      if (Objects.equals(r.getPath(), path) || Objects.equals(r.getPath() + "/", path)) continue;
      String name = r.getName();
      if (name == null || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") ||
          !Objects.equals(r.getPath(), path + name + (r.isDirectory() && !name.endsWith("/") ? "/" : "")))
        throw new IOException("Invalid WebDAV resource location");
      if (!names.add(name)) throw new IOException("Duplicate WebDAV resource");
      long size = r.getContentLength() == null ? -1 : r.getContentLength();
      String etag = r.getEtag();
      // Some WebDAV PROPFIND servers omit ETag quotes. Never guess the condition:
      // corroborate that exact opaque value and size with a strong HTTP HEAD ETag.
      if (!r.isDirectory() && WritingCloudService.FILE.matcher(name).matches() && isBareEtag(etag) && dav instanceof ConditionalDavClient verified) {
        ConditionalDavClient.HeadMetadata head = verified.headMetadata(dir + name);
        if (head.etag() != null && head.size() == size && head.etag().equals("\"" + etag + "\"")) etag = head.etag();
      }
      rows.add(new Entry(name, size,
        etag, r.getModified() == null ? "" : r.getModified().toInstant().toString(), r.isDirectory()));
      if (rows.size() > 10000) throw WritingService.bad("远端目录超过10000项，停止清理以保留全部文件");
    }
    rows.sort(Comparator.comparing(Entry::file)); return rows;
  }
  private static boolean isBareEtag(String etag) {
    return etag != null && !etag.isEmpty() && !etag.equals("*") && !etag.startsWith("W/") &&
      etag.matches("[\\x21\\x23-\\x7E\\x80-\\xFF]+");
  }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private static String fingerprint(List<Entry> entries) { return hash(stringify(JSON.valueToTree(entries))); }
  private String binding(Long id, WritingCloudService.Config config, ObjectNode policy) {
    return tx(() -> {
      WritingBook book = writing.book(id);
      return hash(config.url() + "\n" + Objects.toString(config.username(), "") + "\n" + Objects.toString(config.password(), "") + "\n" +
        book.getUid() + "\n" + Objects.toString(book.getRemoteBase(), "") + "\n" + Objects.toString(book.getResolvedHeads(), "") + "\n" + stringify(policy));
    });
  }

  public ObjectNode storage(Long id) throws Exception {
    return operations.run(id, "STORAGE", () -> {
      var config = cloud.config(id); String uid = cloud.bookUid(id); Sardine dav = cloud.client(config);
      try {
        List<Entry> own = inventory(dav, cloud.folder(config, uid));
        List<Entry> shared = inventory(dav, config.url() + "novel-backups/");
        long bytes = 0, metadata = 0, legacy = 0, count = 0, legacyCount = 0, unknown = 0;
        for (Entry e : own) if (!e.directory()) {
          if (e.size() < 0) unknown++;
          if (WritingCloudService.FILE.matcher(e.file()).matches()) { count++; bytes += Math.max(0, e.size()); }
          else metadata += Math.max(0, e.size());
        }
        for (Entry e : shared) if (!e.directory()) { legacyCount++; legacy += Math.max(0, e.size()); if (e.size() < 0) unknown++; }
        ObjectNode out = JSON.createObjectNode().put("bookUid", uid).put("snapshotBytes", bytes).put("snapshotCount", count)
          .put("legacyBytes", legacy).put("legacyCount", legacyCount).put("metadataBytes", metadata)
          .put("totalBytes", bytes + metadata + legacy).put("unknownSizeCount", unknown);
        out.putNull("quotaAvailableBytes"); out.putNull("quotaUsedBytes");
        try { DavQuota q = dav.getQuota(config.url()); if (q != null) {
          if (q.getQuotaAvailableBytes() >= 0) out.put("quotaAvailableBytes", q.getQuotaAvailableBytes());
          if (q.getQuotaUsedBytes() >= 0) out.put("quotaUsedBytes", q.getQuotaUsedBytes());
        }} catch (IOException ignored) { /* Quota is optional; known bytes remain accurate. */ }
        return out;
      } finally { dav.shutdown(); }
    });
  }

  /** Bridge every non-head directly to an existing reachable head, preserving the graph after any partial deletion. */
  static ObjectNode plan(List<Entry> entries, Set<String> localProtected, int keepLast, int keepDays, Instant now) {
    List<String> names = entries.stream().filter(e -> !e.directory()).map(Entry::file).toList();
    Map<String, Entry> metadata = new HashMap<>(); entries.forEach(e -> metadata.put(e.file(), e));
    // DAV timestamps are instants; device-local filename clocks cannot authorize deletion.
    List<WritingCloudService.Version> versions = WritingCloudService.graph(names).stream()
      .sorted(Comparator.comparing((WritingCloudService.Version v) -> modifiedAt(metadata.get(v.file())), Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(WritingCloudService.Version::time, Comparator.reverseOrder()).thenComparing(WritingCloudService.Version::file))
      .toList();
    Map<String, WritingCloudService.Version> byRevision = new HashMap<>();
    Map<String, Set<String>> children = new HashMap<>();
    for (var v : versions) {
      if (byRevision.put(v.revision(), v) != null) throw stale("云端版本标识重复，停止清理");
      children.computeIfAbsent(v.parent(), k -> new TreeSet<>()).add(v.revision());
    }
    for (String name : names) {
      Matcher m = EDGE.matcher(name);
      if (m.matches() && byRevision.containsKey(m.group(2))) children.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
    }
    Set<String> heads = new HashSet<>(); versions.stream().filter(WritingCloudService.Version::head).forEach(v -> heads.add(v.revision()));
    if (!versions.isEmpty() && heads.isEmpty()) throw stale("云端没有可确认的恢复基线，停止清理");
    ObjectNode out = JSON.createObjectNode(); ArrayNode candidates = out.putArray("candidates"), retained = out.putArray("retained");
    long candidateBytes = 0, retainedBytes = 0; int index = 0;
    for (var v : versions) {
      Entry e = metadata.get(v.file()); String reason = null;
      if (v.head()) reason = "云端恢复基线／并行分支";
      else if ("root".equals(v.parent())) reason = "首个完整恢复基线";
      else if (localProtected.contains(v.revision())) reason = "本机当前基线／待解决版本";
      else if (modifiedAt(e) == null) reason = "远端修改时间未知，无法安全判断保留期";
      else if (index < keepLast) reason = "按远端修改时间计的最新保留份数";
      else if (!modifiedAt(e).isBefore(now.minusSeconds(keepDays * 86400L))) reason = "远端修改时间在保留天数范围内";
      if (e.etag() == null || e.etag().equals("\"*\"") || !e.etag().matches("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\"") || e.size() < 0) reason = "缺少强ETag或文件大小，无法安全删除";
      ObjectNode row = JSON.createObjectNode().put("file", v.file()).put("revision", v.revision()).put("size", e.size())
        .put("time", modifiedAt(e) == null ? null : modifiedAt(e).toString()).put("etag", e.etag()).put("reason", reason == null ? "超过保留范围的完整历史快照" : reason);
      if (reason == null) { candidates.add(row); candidateBytes += Math.max(0, e.size()); }
      else { retained.add(row); retainedBytes += Math.max(0, e.size()); }
      index++;
    }
    for (Entry e : entries) if (!WritingCloudService.FILE.matcher(e.file()).matches()) {
      retained.addObject().put("file", e.file()).put("size", e.size()).put("etag", e.etag()).put("reason", "分支标记或未识别文件，永久保留");
      retainedBytes += Math.max(0, e.size());
    }
    ArrayNode bridges = out.putArray("bridgeMarkers");
    if (!candidates.isEmpty()) for (var v : versions) if (!v.head()) {
      String head = reachableHead(v.revision(), children, heads);
      if (head == null) throw stale("版本关系无法追溯到恢复基线，停止清理");
      String marker = v.revision() + "_" + head + ".resolved";
      if (!names.contains(marker)) bridges.add(marker);
    }
    if (entries.size() + bridges.size() > 10000) throw WritingService.bad("分支保护标记将超过目录容量上限；保留全部文件，请先另存完整备份");
    return out.put("protectedCount", retained.size()).put("candidateBytes", candidateBytes).put("retainedBytes", retainedBytes);
  }
  private static Instant modifiedAt(Entry entry) {
    if (entry.modified() == null || entry.modified().isBlank()) return null;
    try { return Instant.parse(entry.modified()); }
    catch (DateTimeException invalid) { return null; }
  }

  private static String reachableHead(String revision, Map<String, Set<String>> children, Set<String> heads) {
    Deque<String> pending = new ArrayDeque<>(); Set<String> seen = new HashSet<>(); pending.add(revision);
    while (!pending.isEmpty()) { String next = pending.removeFirst(); if (!seen.add(next)) continue;
      if (heads.contains(next)) return next; pending.addAll(children.getOrDefault(next, Set.of())); }
    return null;
  }

  public ObjectNode preview(Long id) throws Exception {
    return operations.run(id, "CLEANUP_PREVIEW", () -> {
      var config = cloud.config(id); String uid = cloud.bookUid(id); ObjectNode policy = retention(id);
      String initialBinding = binding(id, config, policy); Set<String> localProtected = tx(() -> {
        WritingBook b = writing.book(id); Set<String> set = new HashSet<>(); if (b.getRemoteBase() != null) set.add(b.getRemoteBase());
        if (b.getResolvedHeads() != null) parse(b.getResolvedHeads()).forEach(r -> set.add(r.asText())); return set;
      });
      Sardine dav = cloud.client(config);
      try {
        List<Entry> entries = inventory(dav, cloud.folder(config, uid));
        ObjectNode result = plan(entries, localProtected, policy.path("keepLast").asInt(), policy.path("keepDays").asInt(), Instant.now());
        List<Entry> legacy = inventory(dav, config.url() + "novel-backups/").stream().filter(e -> !e.directory()).toList();
        String token = UUID.randomUUID().toString(); Instant expires = Instant.now().plusSeconds(600);
        result.put("token", token).put("expiresAt", expires.toString()).put("bookUid", uid).set("policy", policy);
        result.put("legacyProtectedCount", legacy.size()).put("legacyProtectedBytes", legacy.stream().mapToLong(e -> Math.max(0, e.size())).sum())
          .put("message", "只清理当前作品的不可变历史；共享旧备份、恢复基线及分支标记永久保留。预览10分钟有效，不会自动删除。");
        ObjectNode saved = result.deepCopy().put("inventoryFingerprint", fingerprint(entries)).put("binding", initialBinding);
        tx(() -> { BackupCleanup row = new BackupCleanup(); row.setToken(token); row.setNovelId(id); row.setStatus("PREVIEW");
          row.setExpiresAt(expires); row.setPreview(stringify(saved)); em.persist(row); return null; });
        return result;
      } finally { dav.shutdown(); }
    });
  }

  private BackupCleanup cleanup(Long id, String token) {
    BackupCleanup row = em.find(BackupCleanup.class, WritingService.uuid(token), LockModeType.PESSIMISTIC_WRITE);
    if (row == null || !row.getNovelId().equals(id)) throw WritingService.missing(); return row;
  }
  public ObjectNode cancel(Long id, String token) throws Exception {
    return operations.run(id, "CLEANUP_CANCEL", () -> tx(() -> {
      BackupCleanup row = cleanup(id, token);
      if ("CANCELLED".equals(row.getStatus())) return JSON.createObjectNode().put("token", token).put("status", "CANCELLED");
      if (!"PREVIEW".equals(row.getStatus())) throw stale("此清理已开始或结束，不能再取消");
      row.setStatus("CANCELLED"); return JSON.createObjectNode().put("token", token).put("status", "CANCELLED");
    }));
  }

  public ObjectNode execute(Long id, JsonNode body) throws Exception {
    if (!body.path("confirmed").isBoolean() || !body.path("confirmed").asBoolean()) throw WritingService.bad("必须明确确认此清理预览");
    String token = WritingService.uuid(body.path("token").asText()), request = WritingService.uuid(body.path("requestId").asText());
    ObjectNode claim = tx(() -> {
      BackupCleanup row = cleanup(id, token);
      if (row.getRequestId() != null) {
        if (!row.getRequestId().equals(request)) throw stale("此预览已由另一确认请求使用");
        return row.getResult() == null ? JSON.createObjectNode().put("status", "RUNNING").put("token", token).put("requestId", request)
          .put("message", "同一清理请求正在执行；若服务曾中断，请重新预览核对远端，不能重复执行旧请求") : (ObjectNode) parse(row.getResult());
      }
      if (!"PREVIEW".equals(row.getStatus()) || !row.getExpiresAt().isAfter(Instant.now())) throw stale("清理预览已取消或过期，请重新预览");
      row.setRequestId(request); row.setStatus("RUNNING"); return null;
    });
    if (claim != null) return claim;
    long operationId = operations.start(id, "CLEANUP");
    ObjectNode result = JSON.createObjectNode().put("token", token).put("requestId", request).put("operationId", operationId);
    result.putArray("deleted"); result.putArray("uncertainFiles"); result.put("deletedBytes", 0);
    Exception failure = null;
    try {
      tx(() -> {
        // Keep local base/config/policy fixed while the remote exclusive lock protects other devices.
        writing.book(id);
        BackupCleanup row = cleanup(id, token); ObjectNode preview = (ObjectNode) parse(row.getPreview());
        if (!row.getExpiresAt().isAfter(Instant.now())) throw stale("清理预览已过期，请重新预览");
        var config = cloud.config(id); ObjectNode currentPolicy = policyJson(policy(id));
        if (!preview.path("binding").asText().equals(binding(id, config, currentPolicy))) throw stale("作品、配置、保留策略或恢复基线已变化，请重新预览");
        try { performCleanup(id, config, preview, result); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        return null;
      });
      result.put("status", "SUCCEEDED").put("message", "清理完成；恢复基线与分支关系已保留");
    } catch (Exception e) {
      failure = e;
      result.put("status", result.path("deleted").isEmpty() && result.path("uncertainFiles").isEmpty() ? "FAILED" : "PARTIAL")
        .put("message", BackupOperationService.failureMessage(e) + "；请重新预览核对远端，原确认不会重复删除");
    }
    final ObjectNode savedResult = result.deepCopy();
    tx(() -> { BackupCleanup row = cleanup(id, token); row.setStatus(savedResult.path("status").asText()); row.setResult(stringify(savedResult)); return null; });
    operations.finish(operationId, result.path("status").asText(), result.path("stage").asText("FINISHED"), result.path("message").asText(), result, failure);
    if (failure instanceof ResponseStatusException status) throw status;
    return result;
  }

  private void performCleanup(Long id, WritingCloudService.Config config, ObjectNode preview, ObjectNode result) throws IOException {
    String uid = preview.path("bookUid").asText(), dir = cloud.folder(config, uid); Sardine dav = cloud.client(config); String token = null;
    IOException primary = null;
    try {
      result.put("stage", "LOCK");
      if (preview.path("candidates").isEmpty()) return;
      token = dav.lock(dir);
      if (token == null || token.isBlank()) throw new IOException("Missing exclusive lock token");
      result.put("stage", "REVALIDATE");
      if (!preview.path("inventoryFingerprint").asText().equals(fingerprint(inventory(dav, dir)))) throw stale("远端目录已变化，请重新预览");
      // A filename alone is not proof of a usable recovery baseline. Validate all retained heads before deleting anything.
      Set<String> heads = new HashSet<>();
      var original = inventory(dav, dir); var graph = WritingCloudService.graph(original.stream().filter(e -> !e.directory()).map(Entry::file).toList());
      if (!graph.isEmpty() && graph.stream().noneMatch(WritingCloudService.Version::head)) throw new IOException("No recovery baseline");
      for (var version : graph) if (version.head()) {
        token = dav.refreshLock(dir, token, dir);
        JsonNode data = cloud.download(dav, dir, version.file(), uid);
        try { bundles.validateRecoveryBundle(data.path("bundle")); }
        catch (Exception invalid) { throw new IOException("Recovery baseline validation failed", invalid); }
        heads.add(version.revision());
      }
      // Filename-matching foreign or damaged files are never deletion authority.
      for (JsonNode candidate : preview.path("candidates")) {
        token = dav.refreshLock(dir, token, dir);
        JsonNode candidateData = cloud.download(dav, dir, candidate.path("file").asText(), uid);
        try { bundles.validateRecoveryBundle(candidateData.path("bundle")); }
        catch (Exception invalid) { throw new IOException("Candidate backup validation failed", invalid); }
      }
      result.put("stage", "PRESERVE_GRAPH");
      for (JsonNode marker : preview.path("bridgeMarkers")) {
        token = dav.refreshLock(dir, token, dir);
        Map<String, String> headers = Map.of("If", "(<" + token + ">)", "If-None-Match", "*");
        dav.put(dir + marker.asText(), new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), headers);
        try (InputStream verify = dav.get(dir + marker.asText())) {
          if (!JSON.readTree(BackupBundleService.readLimited(verify, 1024)).isObject()) throw new IOException("Graph marker verification failed");
        }
      }
      List<String> bridgeNames = new ArrayList<>(original.stream().filter(e -> !e.directory()).map(Entry::file).toList());
      preview.path("bridgeMarkers").forEach(m -> bridgeNames.add(m.asText()));
      assertHeads(bridgeNames, heads);
      result.put("stage", "DELETE");
      for (JsonNode candidate : preview.path("candidates")) {
        token = dav.refreshLock(dir, token, dir);
        String file = candidate.path("file").asText();
        ((ArrayNode) result.path("uncertainFiles")).add(file); // A lost DELETE response must remain explicitly uncertain.
        dav.delete(dir + file, Map.of("If", "(<" + token + ">)", "If-Match", candidate.path("etag").asText()));
        ((ArrayNode) result.path("uncertainFiles")).removeAll();
        ((ArrayNode) result.path("deleted")).add(file);
        result.put("deletedBytes", result.path("deletedBytes").asLong() + candidate.path("size").asLong());
        bridgeNames.remove(file); assertHeads(bridgeNames, heads);
      }
      assertHeads(inventory(dav, dir).stream().filter(e -> !e.directory()).map(Entry::file).toList(), heads);
    } catch (IOException e) { primary = e; throw e; }
    finally {
      if (token != null) try { dav.unlock(dir, token); }
      catch (IOException unlock) { result.put("unlockWarning", "远端锁释放未确认，短期内请勿重试清理；锁会自动到期"); if (primary != null) primary.addSuppressed(unlock); }
      try { dav.shutdown(); } catch (IOException shutdown) { result.put("connectionWarning", "连接关闭未确认"); }
    }
  }
  private static void assertHeads(List<String> names, Set<String> expected) throws IOException {
    Set<String> actual = new HashSet<>(); WritingCloudService.graph(names).stream().filter(WritingCloudService.Version::head).forEach(v -> actual.add(v.revision()));
    if (!actual.equals(expected)) throw new IOException("Recovery graph verification failed");
  }
}
