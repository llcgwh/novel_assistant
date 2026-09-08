package com.novelwriting.controller;

import com.novelwriting.entity.Foreshadow;
import com.novelwriting.service.ForeshadowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/foreshadows")
@CrossOrigin(origins = "*")
public class ForeshadowController {

    @Autowired
    private ForeshadowService foreshadowService;

    @GetMapping
    public List<Foreshadow> getForeshadowsByNovelId(@PathVariable Long novelId) {
        return foreshadowService.getForeshadowsByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Foreshadow> getForeshadowById(@PathVariable Long novelId, @PathVariable Long id) {
        return foreshadowService.getForeshadowById(novelId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Foreshadow createForeshadow(@PathVariable Long novelId, @RequestBody Foreshadow foreshadow) {
        foreshadow.setNovelId(novelId);
        return foreshadowService.createForeshadow(foreshadow);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Foreshadow> updateForeshadow(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Foreshadow foreshadow) {
        try {
            return ResponseEntity.ok(foreshadowService.updateForeshadow(novelId, id, foreshadow));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteForeshadow(@PathVariable Long novelId, @PathVariable Long id) {
        foreshadowService.deleteForeshadow(novelId, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Foreshadow> searchForeshadows(@PathVariable Long novelId, @RequestParam String keyword) {
        return foreshadowService.searchForeshadows(novelId, keyword);
    }

    @GetMapping("/status/{status}")
    public List<Foreshadow> getForeshadowsByStatus(@PathVariable Long novelId, @PathVariable String status) {
        return foreshadowService.getForeshadowsByStatus(novelId, status);
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Foreshadow> addTagToForeshadow(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(foreshadowService.addTagToForeshadow(novelId, id, tagId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Foreshadow> removeTagFromForeshadow(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(foreshadowService.removeTagFromForeshadow(novelId, id, tagId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<Foreshadow> setForeshadowTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(foreshadowService.setForeshadowTags(novelId, id, tagIds));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
