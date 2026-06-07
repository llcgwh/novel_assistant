package com.novelwriting.service;

import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.novelwriting.entity.Novel;
import com.novelwriting.repository.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class WebDavSyncService {

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private ExportService exportService;

    @Autowired
    private ImportService importService;

    public Map<String, Object> testConnection(String serverUrl, String username, String password) {
        Map<String, Object> result = new HashMap<>();
        try {
            Sardine sardine = SardineFactory.begin(username, password);
            List<com.github.sardine.DavResource> resources = sardine.list(serverUrl);
            result.put("success", true);
            result.put("message", "连接成功，找到 " + resources.size() + " 个资源");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> syncUpload(Long novelId) {
        Map<String, Object> result = new HashMap<>();
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        if (novel.getWebdavServerUrl() == null || novel.getWebdavServerUrl().isEmpty()) {
            result.put("success", false);
            result.put("message", "未配置 WebDAV 服务器");
            return result;
        }

        try {
            Sardine sardine = SardineFactory.begin(novel.getWebdavUsername(), novel.getWebdavPassword());

            byte[] exportData = exportService.exportNovelToJson(novelId);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeTitle = novel.getTitle().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (safeTitle.length() > 40) {
                safeTitle = safeTitle.substring(0, 40);
            }
            String filename = safeTitle + "_" + timestamp + ".json";

            String baseUrl = novel.getWebdavServerUrl();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }

            // 坚果云需要文件放在子目录下，先确保备份目录存在
            String backupDir = baseUrl + "novel-backups/";
            System.out.println("[WebDAV] Ensuring directory: " + backupDir);
            try {
                sardine.createDirectory(backupDir);
                System.out.println("[WebDAV] Directory ready");
            } catch (Exception e) {
                // Directory may already exist, continue
                System.out.println("[WebDAV] Directory exists or create failed: " + e.getMessage());
            }

            String uploadUrl = backupDir + filename;
            System.out.println("[WebDAV Sardine PUT] URL: " + uploadUrl);
            System.out.println("[WebDAV Sardine PUT] Size: " + exportData.length + " bytes");
            sardine.put(uploadUrl, exportData);
            System.out.println("[WebDAV Sardine PUT] Success!");

            novel.setLastWebdavSync(LocalDateTime.now());
            novelRepository.save(novel);

            result.put("success", true);
            result.put("message", "同步上传成功: " + filename);
            result.put("timestamp", novel.getLastWebdavSync().toString());
            result.put("filename", filename);
        } catch (Exception e) {
            System.err.println("[WebDAV Sardine PUT] Error: " + e.getClass().getName() + " - " + e.getMessage());
            result.put("success", false);
            result.put("message", "同步上传失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> syncDownload(Long novelId, String remoteFilename) {
        Map<String, Object> result = new HashMap<>();
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        if (novel.getWebdavServerUrl() == null || novel.getWebdavServerUrl().isEmpty()) {
            result.put("success", false);
            result.put("message", "未配置 WebDAV 服务器");
            return result;
        }

        try {
            Sardine sardine = SardineFactory.begin(novel.getWebdavUsername(), novel.getWebdavPassword());

            String baseUrl = novel.getWebdavServerUrl();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            String backupDir = baseUrl + "novel-backups/";
            String downloadUrl = backupDir + remoteFilename;

            System.out.println("[WebDAV Sardine GET] URL: " + downloadUrl);
            InputStream inputStream = sardine.get(downloadUrl);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            inputStream.close();
            byte[] downloadedData = buffer.toByteArray();
            System.out.println("[WebDAV Sardine GET] Downloaded: " + downloadedData.length + " bytes");

            // Import the downloaded JSON data into the database
            importService.importFromJson(novelId, downloadedData);
            System.out.println("[WebDAV Sardine GET] Import complete");

            novel.setLastWebdavSync(LocalDateTime.now());
            novelRepository.save(novel);

            result.put("success", true);
            result.put("message", "从云端恢复成功: " + remoteFilename + " (" + (downloadedData.length / 1024) + " KB)，数据已导入");
            result.put("timestamp", novel.getLastWebdavSync().toString());
            result.put("size", downloadedData.length);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "从云端下载失败: " + e.getMessage());
        }
        return result;
    }

    public List<Map<String, Object>> getRemoteFiles(Long novelId) {
        List<Map<String, Object>> files = new ArrayList<>();
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        if (novel.getWebdavServerUrl() == null || novel.getWebdavServerUrl().isEmpty()) {
            return files;
        }

        try {
            Sardine sardine = SardineFactory.begin(novel.getWebdavUsername(), novel.getWebdavPassword());
            // List from the novel-backups subdirectory
            String backupDir = novel.getWebdavServerUrl();
            if (!backupDir.endsWith("/")) { backupDir += "/"; }
            backupDir += "novel-backups/";
            List<com.github.sardine.DavResource> resources = sardine.list(backupDir);

            for (com.github.sardine.DavResource resource : resources) {
                if (!resource.isDirectory()) {
                    Map<String, Object> file = new HashMap<>();
                    file.put("name", resource.getName());
                    file.put("path", resource.getPath());
                    file.put("size", resource.getContentLength());
                    file.put("modified", resource.getModified() != null ? resource.getModified().toString() : null);
                    files.add(file);
                }
            }
        } catch (Exception e) {
            // Return empty list on error
        }
        return files;
    }

    public Map<String, Object> getSyncStatus(Long novelId) {
        Map<String, Object> status = new HashMap<>();
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        status.put("serverUrl", novel.getWebdavServerUrl() != null ? novel.getWebdavServerUrl() : "");
        status.put("username", novel.getWebdavUsername() != null ? novel.getWebdavUsername() : "");
        status.put("hasPassword", novel.getWebdavPassword() != null && !novel.getWebdavPassword().isEmpty());
        status.put("autoSync", novel.getWebdavAutoSync() != null ? novel.getWebdavAutoSync() : false);
        status.put("lastSyncTime", novel.getLastWebdavSync() != null ? novel.getLastWebdavSync().toString() : null);
        status.put("configured", novel.getWebdavServerUrl() != null && !novel.getWebdavServerUrl().isEmpty());

        return status;
    }

    public Map<String, Object> deleteRemoteFile(Long novelId, String filename) {
        Map<String, Object> result = new HashMap<>();
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        if (novel.getWebdavServerUrl() == null || novel.getWebdavServerUrl().isEmpty()) {
            result.put("success", false);
            result.put("message", "未配置 WebDAV 服务器");
            return result;
        }

        try {
            Sardine sardine = SardineFactory.begin(novel.getWebdavUsername(), novel.getWebdavPassword());
            String baseUrl = novel.getWebdavServerUrl();
            if (!baseUrl.endsWith("/")) { baseUrl += "/"; }
            String deleteUrl = baseUrl + "novel-backups/" + filename;

            System.out.println("[WebDAV DELETE] URL: " + deleteUrl);
            sardine.delete(deleteUrl);
            System.out.println("[WebDAV DELETE] Success");

            result.put("success", true);
            result.put("message", "已删除: " + filename);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

    public void saveConfig(Long novelId, Map<String, Object> config) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        if (config.containsKey("serverUrl")) {
            novel.setWebdavServerUrl((String) config.get("serverUrl"));
        }
        if (config.containsKey("username")) {
            novel.setWebdavUsername((String) config.get("username"));
        }
        if (config.containsKey("password") && config.get("password") != null
                && !((String) config.get("password")).isEmpty()) {
            novel.setWebdavPassword((String) config.get("password"));
        }
        if (config.containsKey("autoSync")) {
            novel.setWebdavAutoSync((Boolean) config.get("autoSync"));
        }

        novelRepository.save(novel);
    }

}
