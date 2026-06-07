package com.novelwriting.controller;

import com.novelwriting.service.ExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/novels/{novelId}/export")
@CrossOrigin(origins = "*")
public class ExportController {

    @Autowired
    private ExportService exportService;

    @GetMapping("/json")
    public ResponseEntity<byte[]> exportToJson(@PathVariable Long novelId) {
        try {
            byte[] data = exportService.exportNovelToJson(novelId);
            String filename = URLEncoder.encode("novel_export.json", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/markdown")
    public ResponseEntity<byte[]> exportToMarkdown(@PathVariable Long novelId) {
        try {
            byte[] data = exportService.exportNovelToMarkdown(novelId);
            String filename = URLEncoder.encode("novel_export.md", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/characters/markdown")
    public ResponseEntity<byte[]> exportCharactersToMarkdown(@PathVariable Long novelId) {
        try {
            byte[] data = exportService.exportCharactersToMarkdown(novelId);
            String filename = URLEncoder.encode("characters_export.md", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/outlines/markdown")
    public ResponseEntity<byte[]> exportOutlinesToMarkdown(@PathVariable Long novelId) {
        try {
            byte[] data = exportService.exportOutlinesToMarkdown(novelId);
            String filename = URLEncoder.encode("outlines_export.md", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/worldview/markdown")
    public ResponseEntity<byte[]> exportWorldviewToMarkdown(@PathVariable Long novelId) {
        try {
            byte[] data = exportService.exportWorldviewToMarkdown(novelId);
            String filename = URLEncoder.encode("worldview_export.md", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_MARKDOWN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
