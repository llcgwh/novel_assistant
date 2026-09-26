package com.novelwriting.controller;

import com.novelwriting.entity.Novel;
import com.novelwriting.service.NovelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/novels")
@CrossOrigin(origins = "*")
public class NovelController {

    @Autowired
    private NovelService novelService;

    @Autowired private com.novelwriting.service.WritingService writing;
    @GetMapping("/writing-summary")
    public com.fasterxml.jackson.databind.JsonNode writingSummary(@RequestParam(required = false) String date) {
        return date == null ? writing.summaries() : writing.summaries(date);
    }

    @GetMapping
    public List<Novel> getAllNovels() {
        return novelService.getAllNovels();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Novel> getNovelById(@PathVariable Long id) {
        return novelService.getNovelById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Novel createNovel(@RequestBody Novel novel) {
        return novelService.createNovel(novel);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Novel> updateNovel(@PathVariable Long id, @RequestBody Novel novel) {
        try {
            return ResponseEntity.ok(novelService.updateNovel(id, novel));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNovel(@PathVariable Long id) {
        novelService.deleteNovel(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Novel> searchNovels(@RequestParam String title) {
        return novelService.searchNovels(title);
    }

    @GetMapping("/status/{status}")
    public List<Novel> getNovelsByStatus(@PathVariable String status) {
        return novelService.getNovelsByStatus(status);
    }
}
