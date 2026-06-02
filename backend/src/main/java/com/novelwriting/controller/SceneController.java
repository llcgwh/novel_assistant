package com.novelwriting.controller;

import com.novelwriting.entity.Scene;
import com.novelwriting.service.SceneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/scenes")
@CrossOrigin(origins = "*")
public class SceneController {

    @Autowired
    private SceneService sceneService;

    @GetMapping
    public List<Scene> getScenesByNovelId(@PathVariable Long novelId) {
        return sceneService.getScenesByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Scene> getSceneById(@PathVariable Long novelId, @PathVariable Long id) {
        return sceneService.getSceneById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Scene createScene(@PathVariable Long novelId, @RequestBody Scene scene) {
        scene.setNovelId(novelId);
        return sceneService.createScene(scene);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Scene> updateScene(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Scene scene) {
        try {
            return ResponseEntity.ok(sceneService.updateScene(id, scene));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteScene(@PathVariable Long novelId, @PathVariable Long id) {
        sceneService.deleteScene(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Scene> searchScenes(@PathVariable Long novelId, @RequestParam String keyword) {
        return sceneService.searchScenes(novelId, keyword);
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Scene> addTagToScene(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(sceneService.addTagToScene(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Scene> removeTagFromScene(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(sceneService.removeTagFromScene(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<Scene> setSceneTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(sceneService.setSceneTags(id, tagIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/locations/{locationId}")
    public ResponseEntity<Scene> addLocationToScene(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long locationId) {
        try {
            return ResponseEntity.ok(sceneService.addMapLocationToScene(id, locationId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/locations/{locationId}")
    public ResponseEntity<Scene> removeLocationFromScene(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long locationId) {
        try {
            return ResponseEntity.ok(sceneService.removeMapLocationFromScene(id, locationId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/locations")
    public ResponseEntity<Scene> setSceneLocations(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> locationIds) {
        try {
            return ResponseEntity.ok(sceneService.setSceneMapLocations(id, locationIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
