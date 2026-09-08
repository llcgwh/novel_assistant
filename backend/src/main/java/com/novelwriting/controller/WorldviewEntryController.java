package com.novelwriting.controller;

import com.novelwriting.entity.WorldviewEntry;
import com.novelwriting.service.WorldviewEntryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/worldview")
@CrossOrigin(origins = "*")
public class WorldviewEntryController {

    @Autowired
    private WorldviewEntryService worldviewEntryService;

    @GetMapping
    public List<WorldviewEntry> getEntries(
            @PathVariable Long novelId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        if (category != null && !category.isEmpty() || keyword != null && !keyword.isEmpty()) {
            return worldviewEntryService.searchEntries(novelId, category, keyword);
        }
        return worldviewEntryService.getEntriesByNovelId(novelId);
    }

    @GetMapping("/categories")
    public List<String> getCategories(@PathVariable Long novelId) {
        // Return distinct categories used by this novel
        return worldviewEntryService.getEntriesByNovelId(novelId)
                .stream()
                .map(WorldviewEntry::getCategory)
                .distinct()
                .sorted()
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorldviewEntry> getEntryById(@PathVariable Long novelId, @PathVariable Long id) {
        return worldviewEntryService.getEntryById(novelId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public WorldviewEntry createEntry(@PathVariable Long novelId, @RequestBody WorldviewEntry entry) {
        entry.setNovelId(novelId);
        return worldviewEntryService.createEntry(entry);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorldviewEntry> updateEntry(
            @PathVariable Long novelId, @PathVariable Long id, @RequestBody WorldviewEntry entry) {
        try {
            return ResponseEntity.ok(worldviewEntryService.updateEntry(novelId, id, entry));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long novelId, @PathVariable Long id) {
        worldviewEntryService.deleteEntry(novelId, id);
        return ResponseEntity.ok().build();
    }

    // Tag management
    @PutMapping("/{id}/tags")
    public ResponseEntity<WorldviewEntry> setEntryTags(
            @PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(worldviewEntryService.setEntryTags(novelId, id, tagIds));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<WorldviewEntry> addTagToEntry(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.addTagToEntry(novelId, id, tagId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<WorldviewEntry> removeTagFromEntry(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.removeTagFromEntry(novelId, id, tagId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Character relations
    @PostMapping("/{id}/characters/{characterId}")
    public ResponseEntity<WorldviewEntry> addCharacterRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long characterId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.addCharacterRelation(novelId, id, characterId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/characters/{characterId}")
    public ResponseEntity<WorldviewEntry> removeCharacterRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long characterId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.removeCharacterRelation(novelId, id, characterId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Scene relations
    @PostMapping("/{id}/scenes/{sceneId}")
    public ResponseEntity<WorldviewEntry> addSceneRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long sceneId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.addSceneRelation(novelId, id, sceneId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/scenes/{sceneId}")
    public ResponseEntity<WorldviewEntry> removeSceneRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long sceneId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.removeSceneRelation(novelId, id, sceneId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // Map location relations
    @PostMapping("/{id}/locations/{locationId}")
    public ResponseEntity<WorldviewEntry> addMapLocationRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long locationId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.addMapLocationRelation(novelId, id, locationId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/locations/{locationId}")
    public ResponseEntity<WorldviewEntry> removeMapLocationRelation(
            @PathVariable Long novelId, @PathVariable Long id, @PathVariable Long locationId) {
        try {
            return ResponseEntity.ok(worldviewEntryService.removeMapLocationRelation(novelId, id, locationId));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
