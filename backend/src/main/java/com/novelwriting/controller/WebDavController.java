package com.novelwriting.controller;

import com.novelwriting.service.WebDavSyncService;
import com.novelwriting.service.BackupMaintenanceService;
import com.novelwriting.service.BackupOperationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.server.ResponseStatusException;
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

    @Autowired private BackupMaintenanceService maintenance;
    @Autowired private BackupOperationService operations;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long novelId, @RequestBody Map<String, String> config) {
        Map<String, Object> result = webDavSyncService.testConnection(
                novelId,
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
    public ResponseEntity<List<Map<String, Object>>> getRemoteFiles(@PathVariable Long novelId) throws Exception {
        List<Map<String, Object>> files = webDavSyncService.getRemoteFiles(novelId);
        return ResponseEntity.ok(files);
    }

    @DeleteMapping("/files")
    public ResponseEntity<Map<String, Object>> deleteRemoteFile(@PathVariable Long novelId, @RequestBody Map<String, String> body) {
        Map<String, Object> result = webDavSyncService.deleteRemoteFile(novelId, body.get("filename"));
        return ResponseEntity.ok(result);
    }
    @GetMapping("/operations")
    public JsonNode operations(@PathVariable Long novelId, @RequestParam(defaultValue = "30") int limit,
                               @RequestParam(required = false) Long before) {
        return operations.history(novelId, limit, before);
    }
    @GetMapping("/storage")
    public JsonNode storage(@PathVariable Long novelId) throws Exception { return maintenance.storage(novelId); }
    @GetMapping("/retention")
    public JsonNode retention(@PathVariable Long novelId) { return maintenance.retention(novelId); }
    @PutMapping("/retention")
    public JsonNode retention(@PathVariable Long novelId, @RequestBody JsonNode body) { return maintenance.retention(novelId, body); }
    @PostMapping("/cleanup/preview")
    public JsonNode cleanupPreview(@PathVariable Long novelId) throws Exception { return maintenance.preview(novelId); }
    @PostMapping("/cleanup/execute")
    public JsonNode cleanupExecute(@PathVariable Long novelId, @RequestBody JsonNode body) throws Exception { return maintenance.execute(novelId, body); }
    @DeleteMapping("/cleanup/{token}")
    public JsonNode cleanupCancel(@PathVariable Long novelId, @PathVariable String token) throws Exception { return maintenance.cancel(novelId, token); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", BackupOperationService.failureMessage(e)));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failed(Exception e) {
        return ResponseEntity.status(502).body(Map.of("message", BackupOperationService.failureMessage(e)));
    }
}
