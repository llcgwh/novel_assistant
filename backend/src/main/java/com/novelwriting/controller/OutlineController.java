package com.novelwriting.controller;

import com.novelwriting.entity.Outline;
import com.novelwriting.service.OutlineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/outlines")
@CrossOrigin(origins = "*")
public class OutlineController {

    @Autowired
    private OutlineService outlineService;

    @GetMapping
    public List<Outline> getOutlinesByNovelId(@PathVariable Long novelId) {
        return outlineService.getOutlinesByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Outline> getOutlineById(@PathVariable Long novelId, @PathVariable Long id) {
        return outlineService.getOutlineById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Outline createOutline(@PathVariable Long novelId, @RequestBody Outline outline) {
        outline.setNovelId(novelId);
        return outlineService.createOutline(outline);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Outline> updateOutline(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Outline outline) {
        try {
            return ResponseEntity.ok(outlineService.updateOutline(id, outline));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOutline(@PathVariable Long novelId, @PathVariable Long id) {
        outlineService.deleteOutline(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Outline> searchOutlines(@PathVariable Long novelId, @RequestParam String keyword) {
        return outlineService.searchOutlines(novelId, keyword);
    }

    @GetMapping("/status/{status}")
    public List<Outline> getOutlinesByStatus(@PathVariable Long novelId, @PathVariable String status) {
        return outlineService.getOutlinesByStatus(novelId, status);
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Outline> addTagToOutline(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(outlineService.addTagToOutline(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Outline> removeTagFromOutline(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(outlineService.removeTagFromOutline(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<Outline> setOutlineTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(outlineService.setOutlineTags(id, tagIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
