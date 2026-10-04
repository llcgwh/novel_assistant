package com.novelwriting.controller;

import com.novelwriting.service.BackupBundleService;
import com.novelwriting.service.BackupOperationService;
import static com.novelwriting.service.WritingDocuments.JSON;
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
    @Autowired private BackupOperationService operations;

    @GetMapping
    public ResponseEntity<?> download(@PathVariable Long novelId) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=novel.backup.json")
                    .body(operations.run(novelId, "BACKUP_EXPORT", () -> backups.exportBundle(novelId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", BackupOperationService.failureMessage(e)));
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> restore(@PathVariable Long novelId, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(operations.run(novelId, "BACKUP_RESTORE", () -> {
                byte[] data = BackupBundleService.readLimited(request.getInputStream(), BackupBundleService.MAX_BACKUP_BYTES);
                Long copy = backups.restoreWithCopy(novelId, data);
                return JSON.createObjectNode().put("message", "恢复成功；原稿保留为作品 #" + copy).put("copyNovelId", copy);
            }));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", BackupOperationService.failureMessage(e)));
        }
    }
}
