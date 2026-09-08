package com.novelwriting.controller;

import com.novelwriting.service.BackupBundleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/novels/{novelId}/backup")
@CrossOrigin(origins = "*")
public class BackupController {
    @Autowired private BackupBundleService backups;

    @GetMapping
    public ResponseEntity<?> download(@PathVariable Long novelId) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=novel.backup.json")
                    .body(backups.exportBundle(novelId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "备份失败: " + e.getMessage()));
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> restore(@PathVariable Long novelId, HttpServletRequest request) {
        try {
            byte[] data = BackupBundleService.readLimited(request.getInputStream(), BackupBundleService.MAX_BACKUP_BYTES);
            backups.restore(novelId, data);
            return ResponseEntity.ok(Map.of("message", "恢复成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "恢复失败: " + e.getMessage()));
        }
    }
}
