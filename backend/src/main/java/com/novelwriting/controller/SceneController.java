package com.novelwriting.controller;

import com.novelwriting.entity.Scene;
import com.novelwriting.service.SceneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/scenes")
@CrossOrigin(origins = "*")
public class SceneController {

    @Autowired
    private SceneService sceneService;

    @GetMapping
    public List<Scene> getAllScenes() {
        return sceneService.getAllScenes();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Scene> getSceneById(@PathVariable Long id) {
        return sceneService.getSceneById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Scene createScene(@RequestBody Scene scene) {
        return sceneService.createScene(scene);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Scene> updateScene(@PathVariable Long id, @RequestBody Scene scene) {
        try {
            return ResponseEntity.ok(sceneService.updateScene(id, scene));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteScene(@PathVariable Long id) {
        sceneService.deleteScene(id);
        return ResponseEntity.ok().build();
    }
}
