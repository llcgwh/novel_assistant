package com.novelwriting.service;

import com.github.sardine.*;
import com.novelwriting.entity.Novel;
import com.novelwriting.repository.NovelRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.novelwriting.service.WritingDocuments.JSON;

@Service
public class WebDavSyncService {
  @Autowired private NovelRepository novelRepository;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
  @Autowired private BackupBundleService backupBundleService;
  @Autowired private BackupOperationService operations;

  Sardine client(String username, String password) { return new ConditionalDavClient(username, password); }

  private Map<String, Object> recorded(Long id, String type, BackupOperationService.Work<Map<String, Object>> work) {
    long operation = operations.start(id, type);
    try {
      Map<String, Object> result = work.run(); result.put("operationId", operation);
      operations.finish(operation, "SUCCEEDED", "FINISHED", Objects.toString(result.get("message"), "操作完成"), JSON.valueToTree(result), null);
      return result;
    } catch (Exception e) {
      String message = BackupOperationService.failureMessage(e);
      operations.finish(operation, "FAILED", type, message, null, e);
      return new LinkedHashMap<>(Map.of("success", false, "message", message, "operationId", operation));
    }
  }
  private Novel configured(Long id) {
    Novel n = novelRepository.findById(id).orElseThrow(WritingService::missing);
    if (n.getWebdavServerUrl() == null || n.getWebdavServerUrl().isBlank()) throw WritingService.bad("请先在设置页配置 WebDAV");
    if (!n.getWebdavServerUrl().matches("https?://.+")) throw WritingService.bad("WebDAV 地址必须使用 HTTP 或 HTTPS");
    return n;
  }
  private String dir(Novel n) { String url = n.getWebdavServerUrl(); return (url.endsWith("/") ? url : url + "/") + "novel-backups/"; }
  private void filename(String name) {
    if (name == null || name.length() > 255 || !name.matches("[a-zA-Z0-9_.-]+\\.json") || name.contains("..")) throw WritingService.bad("备份文件名无效");
  }
  public Map<String, Object> testConnection(Long id, String serverUrl, String username, String password) {
    return recorded(id, "CONNECTION_TEST", () -> {
      if (serverUrl == null || !serverUrl.matches("https?://.+")) throw WritingService.bad("WebDAV 地址必须使用 HTTP 或 HTTPS");
      Sardine dav = client(username, password);
      try { int count = dav.list(serverUrl).size(); return new LinkedHashMap<>(Map.of("success", true, "message", "连接成功，找到 " + count + " 个资源")); }
      finally { dav.shutdown(); }
    });
  }
  public Map<String, Object> syncUpload(Long id) {
    return recorded(id, "WEBDAV_UPLOAD", () -> {
      Novel n = configured(id); byte[] data = backupBundleService.exportBundle(id);
      String title = n.getTitle().replaceAll("[^a-zA-Z0-9_\\-]", "_"); title = title.substring(0, Math.min(40, title.length()));
      String name = title + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID() + ".backup.json";
      Sardine dav = client(n.getWebdavUsername(), n.getWebdavPassword());
      try {
        String folder = dir(n);
        if (!dav.exists(folder)) try { dav.createDirectory(folder); } catch (IOException e) { if (!dav.exists(folder)) throw e; }
        dav.put(folder + name, new ByteArrayInputStream(data), Map.of("If-None-Match", "*"));
        try (InputStream stream = dav.get(folder + name)) {
          if (!Arrays.equals(data, BackupBundleService.readLimited(stream, BackupBundleService.MAX_BACKUP_BYTES))) throw new IOException("Backup verification failed");
        }
        LocalDateTime syncedAt = LocalDateTime.now(); novelRepository.updateLastWebdavSync(id, syncedAt);
        return new LinkedHashMap<>(Map.of("success", true, "message", "同步上传成功: " + name, "timestamp", syncedAt.toString(), "filename", name, "size", data.length));
      } finally { dav.shutdown(); }
    });
  }
  public Map<String, Object> syncDownload(Long id, String name) {
    return recorded(id, "WEBDAV_RESTORE", () -> {
      filename(name); Novel n = configured(id); Sardine dav = client(n.getWebdavUsername(), n.getWebdavPassword());
      byte[] data;
      try (InputStream input = dav.get(dir(n) + name)) { data = BackupBundleService.readLimited(input, BackupBundleService.MAX_BACKUP_BYTES); }
      finally { dav.shutdown(); }
      Long copy = backupBundleService.restoreWithCopy(id, data);
      LocalDateTime syncedAt = LocalDateTime.now(); novelRepository.updateLastWebdavSync(id, syncedAt);
      return new LinkedHashMap<>(Map.of("success", true, "message", "从云端恢复成功；原稿保留为作品 #" + copy,
        "timestamp", syncedAt.toString(), "size", data.length, "copyNovelId", copy));
    });
  }
  public List<Map<String, Object>> getRemoteFiles(Long id) throws Exception {
    return operations.run(id, "WEBDAV_LIST", () -> {
      Novel n = configured(id); Sardine dav = client(n.getWebdavUsername(), n.getWebdavPassword());
      try {
        if (!dav.exists(dir(n))) return List.of();
        List<Map<String, Object>> files = new ArrayList<>();
        for (DavResource r : dav.list(dir(n))) if (!r.isDirectory()) {
          Map<String, Object> file = new LinkedHashMap<>(); file.put("name", r.getName()); file.put("path", r.getPath());
          file.put("size", r.getContentLength()); file.put("modified", r.getModified() == null ? null : r.getModified().toInstant().toString()); files.add(file);
        }
        return files;
      } finally { dav.shutdown(); }
    });
  }
  public Map<String, Object> getSyncStatus(Long id) {
    Novel n = novelRepository.findById(id).orElseThrow(WritingService::missing); Map<String, Object> out = new LinkedHashMap<>();
    out.put("serverUrl", Objects.toString(n.getWebdavServerUrl(), "")); out.put("username", Objects.toString(n.getWebdavUsername(), ""));
    out.put("hasPassword", n.getWebdavPassword() != null && !n.getWebdavPassword().isEmpty());
    out.put("autoSync", Boolean.TRUE.equals(n.getWebdavAutoSync())); out.put("lastSyncTime", n.getLastWebdavSync() == null ? null : n.getLastWebdavSync().toString());
    out.put("configured", n.getWebdavServerUrl() != null && !n.getWebdavServerUrl().isBlank()); return out;
  }
  public Map<String, Object> deleteRemoteFile(Long id, String filename) {
    throw BackupMaintenanceService.stale("直接删除已停用；请使用清理预览并明确确认。共享旧备份永久保留");
  }
  @org.springframework.transaction.annotation.Transactional
  public void saveConfig(Long id, Map<String, Object> config) {
    Novel n = em.find(Novel.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    if (n == null) throw WritingService.missing();
    if (config.containsKey("serverUrl")) n.setWebdavServerUrl((String) config.get("serverUrl"));
    if (config.containsKey("username")) n.setWebdavUsername((String) config.get("username"));
    if (config.get("password") instanceof String password && !password.isEmpty()) n.setWebdavPassword(password);
    if (config.containsKey("autoSync")) n.setWebdavAutoSync((Boolean) config.get("autoSync"));
    novelRepository.save(n);
  }
}
