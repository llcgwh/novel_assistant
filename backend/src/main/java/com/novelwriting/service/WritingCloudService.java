package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.github.sardine.*;
import com.novelwriting.entity.*;
import jakarta.persistence.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable snapshots form a parent graph. Concurrent uploads create visible branches, never overwrites. */
@Service
public class WritingCloudService {

  @PersistenceContext
  private EntityManager em;

  @Autowired
  private WritingService writing;

  @Autowired
  private BackupBundleService bundles;

  @Autowired
  private PlatformTransactionManager transactions;

  private final Map<Long, Object> locks = new ConcurrentHashMap<>();
  static final Pattern FILE = Pattern.compile(
    "^(\\d{14})_([a-f0-9-]{36})_(root|[a-f0-9-]{36})\\.ink\\.json$"
  );

  record Config(String url, String username, String password) {}

  record Snapshot(
    String uid,
    String parent,
    long sequence,
    String title,
    byte[] bundle
  ) {}

  record Version(
    String file,
    String revision,
    String parent,
    String time,
    boolean head
  ) {}

  private <T> T tx(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(status -> work.get());
  }

  private Config config(Long id) {
    return tx(() -> {
      Novel n = em.find(Novel.class, id);
      if (n == null) throw WritingService.missing();
      String url = n.getWebdavServerUrl();
      if (url == null || url.isBlank()) throw WritingService.bad(
        "请先在设置页配置 WebDAV"
      );
      if (!url.matches("https?://.+")) throw WritingService.bad(
        "WebDAV 地址必须使用 HTTP 或 HTTPS"
      );
      return new Config(
        url.endsWith("/") ? url : url + "/",
        n.getWebdavUsername(),
        n.getWebdavPassword()
      );
    });
  }

  private String folder(Config c, String book) {
    return c.url() + "novel-backups/ink-" + WritingService.uuid(book) + "/";
  }

  private String bookUid(Long id) {
    return tx(() -> writing.book(id).getUid());
  }

  private Sardine client(Config c) {
    return new com.github.sardine.impl.SardineImpl(
      org.apache.http.impl.client.HttpClients.custom().setDefaultRequestConfig(
        org.apache.http.client.config.RequestConfig.custom()
          .setConnectTimeout(10000)
          .setConnectionRequestTimeout(10000)
          .setSocketTimeout(45000)
          .build()
      ),
      c.username(),
      c.password()
    );
  }

  private void directory(Sardine dav, String url) throws IOException {
    if (!dav.exists(url)) {
      try {
        dav.createDirectory(url);
      } catch (IOException e) {
        if (!dav.exists(url)) throw e;
      }
    }
  }

  static List<Version> graph(List<String> names) {
    List<Version> rows = new ArrayList<>();
    Set<String> parents = new HashSet<>();
    for (String name : names) {
      Matcher m = FILE.matcher(name);
      if (m.matches()) {
        rows.add(new Version(name, m.group(2), m.group(3), m.group(1), false));
        parents.add(m.group(3));
      }
    }
    Set<String> revisions = new HashSet<>();
    rows.forEach(v -> revisions.add(v.revision()));
    for (String name : names) {
      Matcher edge = Pattern.compile(
        "^([a-f0-9-]{36})_([a-f0-9-]{36})\\.resolved$"
      ).matcher(name);
      if (edge.matches() && revisions.contains(edge.group(2))) parents.add(
        edge.group(1)
      );
    }
    return rows
      .stream()
      .map(v ->
        new Version(
          v.file(),
          v.revision(),
          v.parent(),
          v.time(),
          !parents.contains(v.revision())
        )
      )
      .sorted(
        Comparator.comparing(Version::time)
          .reversed()
          .thenComparing(Version::file)
      )
      .toList();
  }

  private List<Version> list(Sardine dav, String dir) throws IOException {
    if (!dav.exists(dir)) return List.of();
    return graph(
      dav
        .list(dir)
        .stream()
        .filter(r -> !r.isDirectory())
        .map(DavResource::getName)
        .toList()
    );
  }

  public ArrayNode versions(Long id, String remote) throws IOException {
    Config c = config(id);
    String uid = remote == null || remote.isBlank()
      ? bookUid(id)
      : WritingService.uuid(remote);
    Sardine dav = client(c);
    try {
      ArrayNode out = JSON.createArrayNode();
      for (Version v : list(dav, folder(c, uid)).stream().limit(100).toList())
        out
          .addObject()
          .put("file", v.file())
          .put("revision", v.revision())
          .put(
            "time",
            v.time().substring(0, 4) +
              "-" +
              v.time().substring(4, 6) +
              "-" +
              v.time().substring(6, 8) +
              " " +
              v.time().substring(8, 10) +
              ":" +
              v.time().substring(10, 12) +
              ":" +
              v.time().substring(12)
          )
          .put("head", v.head());
      return out;
    } finally {
      dav.shutdown();
    }
  }

  private JsonNode download(Sardine dav, String dir, String file, String book)
    throws IOException {
    Matcher name = FILE.matcher(file);
    if (!name.matches()) throw WritingService.bad("云端文件名无效");
    JsonNode data;
    try (InputStream input = dav.get(dir + file)) {
      data = JSON.readTree(
        BackupBundleService.readLimited(
          input,
          BackupBundleService.MAX_BACKUP_BYTES + 1024 * 1024
        )
      );
    }
    if (
      !data.path("format").asText().equals("ink-cloud-v1") ||
      !data.path("bookUid").asText().equals(book) ||
      !data.path("revision").asText().equals(name.group(2))
    ) throw WritingService.bad("云端版本身份不匹配");
    BackupValidator.validate(data.path("bundle").path("data"));
    return data;
  }

  public ObjectNode preview(Long id, String remote, String file)
    throws IOException {
    Config c = config(id);
    String uid = WritingService.uuid(remote);
    Sardine dav = client(c);
    try {
      JsonNode data = download(dav, folder(c, uid), file, uid);
      ObjectNode result = JSON.createObjectNode()
        .put("title", data.path("title").asText())
        .put("revision", data.path("revision").asText());
      ArrayNode chapters = result.putArray("chapters");
      for (JsonNode row : data
        .path("bundle")
        .path("data")
        .path("writing")
        .path("chapters"))
        if (!row.path("deleted").asBoolean()) {
          ObjectNode copy = row.deepCopy();
          copy.remove(List.of("doc", "notes", "links"));
          copy.put("text", text(row.path("doc")));
          chapters.add(copy);
        }
      return result;
    } finally {
      dav.shutdown();
    }
  }

  private Snapshot capture(Long id) {
    return tx(() -> {
      WritingBook book = writing.book(id);
      try {
        return new Snapshot(
          book.getUid(),
          book.getRemoteBase(),
          book.getChangeSequence(),
          em.find(Novel.class, id).getTitle(),
          bundles.exportBundle(id)
        );
      } catch (Exception e) {
        throw new IllegalStateException("无法生成完整作品备份", e);
      }
    });
  }

  public ObjectNode push(Long id) throws IOException {
    synchronized (locks.computeIfAbsent(id, key -> new Object())) {
      Config c = config(id);
      Snapshot snapshot = capture(id);
      Sardine dav = client(c);
      try {
        directory(dav, c.url() + "novel-backups/");
        String dir = folder(c, snapshot.uid());
        directory(dav, dir);
        List<Version> heads = list(dav, dir)
          .stream()
          .filter(Version::head)
          .toList();
        Set<String> resolved = tx(() -> {
          String stored = writing.book(id).getResolvedHeads();
          Set<String> set = new HashSet<>();
          if (stored != null) parse(stored).forEach(v -> set.add(v.asText()));
          return set;
        });
        Set<String> actual = new HashSet<>();
        heads.forEach(v -> actual.add(v.revision()));
        boolean resolving = !resolved.isEmpty() && resolved.equals(actual);
        if (
          !resolving &&
          (heads.size() > 1 ||
            (heads.size() == 1 &&
              !Objects.equals(heads.get(0).revision(), snapshot.parent())))
        ) {
          note(id, "云端有新的或并行版本，请比较后恢复；本机正文未覆盖");
          throw WritingService.conflict();
        }
        boolean clean = tx(() -> {
          WritingBook b = writing.book(id);
          return (
            b.getRemoteBase() != null &&
            b.getSyncedSequence() >= snapshot.sequence()
          );
        });
        if (clean) return JSON.createObjectNode().put(
          "message",
          "当前作品已同步"
        );
        String revision = UUID.randomUUID().toString(),
          stamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
          );
        String name =
          stamp +
          "_" +
          revision +
          "_" +
          (snapshot.parent() == null ? "root" : snapshot.parent()) +
          ".ink.json";
        ObjectNode data = JSON.createObjectNode()
          .put("format", "ink-cloud-v1")
          .put("bookUid", snapshot.uid())
          .put("revision", revision)
          .put("parent", snapshot.parent())
          .put("title", snapshot.title())
          .put("savedAt", Instant.now().toString());
        data.set("bundle", JSON.readTree(snapshot.bundle()));
        byte[] bytes = JSON.writeValueAsBytes(data);
        dav.put(dir + name, bytes);
        // Read back the exact immutable file before acknowledging a successful upload.
        JsonNode verified = download(dav, dir, name, snapshot.uid());
        if (
          !verified.path("revision").asText().equals(revision)
        ) throw new IOException("云端写入校验失败");
        if (resolving) for (String parent : resolved)
          if (!Objects.equals(parent, snapshot.parent())) dav.put(
            dir + parent + "_" + revision + ".resolved",
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
          );
        tx(() -> {
          WritingBook b = writing.book(id);
          b.setRemoteBase(revision);
          b.setResolvedHeads(null);
          b.setSyncedSequence(snapshot.sequence());
          b.setLastSync(LocalDateTime.now());
          b.setSyncMessage("完整作品已同步");
          return null;
        });
        return JSON.createObjectNode()
          .put("message", "完整作品已同步")
          .put("file", name);
      } finally {
        dav.shutdown();
      }
    }
  }

  public ObjectNode pull(Long id, String remote, String file)
    throws IOException {
    synchronized (locks.computeIfAbsent(id, key -> new Object())) {
      Config c = config(id);
      String uid = WritingService.uuid(remote);
      Sardine dav = client(c);
      JsonNode cloud;
      List<String> heads;
      try {
        cloud = download(dav, folder(c, uid), file, uid);
        heads = list(dav, folder(c, uid))
          .stream()
          .filter(Version::head)
          .map(Version::revision)
          .toList();
      } finally {
        dav.shutdown();
      }
      return tx(() -> {
        WritingBook target = writing.book(id);
        Novel source = em.find(Novel.class, id);
        try {
          byte[] local = bundles.exportBundle(id);
          Novel copy = new Novel();
          String copyTitle =
            source
              .getTitle()
              .substring(0, Math.min(150, source.getTitle().length())) +
            "（同步前副本 " +
            LocalDateTime.now().format(
              DateTimeFormatter.ofPattern("MMdd-HHmmss")
            ) +
            "）";
          copy.setTitle(copyTitle);
          copy.setDescription(source.getDescription());
          em.persist(copy);
          em.flush();
          bundles.restore(copy.getId(), local);
          copy.setTitle(copyTitle);
          bundles.restore(id, JSON.writeValueAsBytes(cloud.path("bundle")));
          target = writing.book(id);
          target.setUid(uid);
          target.setRemoteBase(cloud.path("revision").asText());
          target.setResolvedHeads(stringify(heads));
          target.setSyncedSequence(target.getChangeSequence() - 1);
          target.setLastSync(LocalDateTime.now());
          target.setSyncMessage(
            "已恢复云端版本；本机原稿保留为作品 #" +
              copy.getId() +
              "。下次上传将记录本次版本选择"
          );
          return JSON.createObjectNode()
            .put("message", target.getSyncMessage())
            .put("copyNovelId", copy.getId());
        } catch (Exception e) {
          if (
            e instanceof
              org.springframework.web.server.ResponseStatusException response
          ) throw response;
          throw new IllegalStateException("恢复失败，当前作品未更改", e);
        }
      });
    }
  }

  public void note(Long id, String message) {
    tx(() -> {
      WritingBook b = writing.book(id);
      b.setSyncMessage(message);
      return null;
    });
  }

  public List<Long> pending() {
    return tx(() ->
      em
        .createQuery(
          "select b.novelId from WritingBook b,Novel n where n.id=b.novelId and n.webdavAutoSync=true and b.changeSequence>b.syncedSequence",
          Long.class
        )
        .getResultList()
    );
  }

  public void markResourceChange(Long id) {
    tx(() -> {
      writing.touch(writing.book(id), false);
      return null;
    });
  }
}
