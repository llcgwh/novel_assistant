package com.novelwriting.controller;

import com.novelwriting.service.WebDavSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/novels/{novelId}/webdav")
@CrossOrigin(origins = "*")
public class WebDavController {

    @Autowired
    private WebDavSyncService webDavSyncService;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long novelId, @RequestBody Map<String, String> config) {
        Map<String, Object> result = webDavSyncService.testConnection(
                config.get("serverUrl"),
                config.get("username"),
                config.get("password")
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/upload")
    public ResponseEntity<Map<String, Object>> syncUpload(@PathVariable Long novelId) {
        Map<String, Object> result = webDavSyncService.syncUpload(novelId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync/download")
    public ResponseEntity<Map<String, Object>> syncDownload(@PathVariable Long novelId, @RequestBody Map<String, String> body) {
        Map<String, Object> result = webDavSyncService.syncDownload(novelId, body.get("filename"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long novelId) {
        Map<String, Object> status = webDavSyncService.getSyncStatus(novelId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, String>> saveConfig(@PathVariable Long novelId, @RequestBody Map<String, Object> config) {
        webDavSyncService.saveConfig(novelId, config);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> getRemoteFiles(@PathVariable Long novelId) {
        List<Map<String, Object>> files = webDavSyncService.getRemoteFiles(novelId);
        return ResponseEntity.ok(files);
    }

    @DeleteMapping("/files")
    public ResponseEntity<Map<String, Object>> deleteRemoteFile(@PathVariable Long novelId, @RequestBody Map<String, String> body) {
        Map<String, Object> result = webDavSyncService.deleteRemoteFile(novelId, body.get("filename"));
        return ResponseEntity.ok(result);
    }
}
