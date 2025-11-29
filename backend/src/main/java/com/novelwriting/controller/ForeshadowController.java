package com.novelwriting.controller;

import com.novelwriting.entity.Foreshadow;
import com.novelwriting.service.ForeshadowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/foreshadows")
@CrossOrigin(origins = "*")
public class ForeshadowController {

    @Autowired
    private ForeshadowService foreshadowService;

    @GetMapping
    public List<Foreshadow> getAllForeshadows() {
        return foreshadowService.getAllForeshadows();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Foreshadow> getForeshadowById(@PathVariable Long id) {
        return foreshadowService.getForeshadowById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Foreshadow createForeshadow(@RequestBody Foreshadow foreshadow) {
        return foreshadowService.createForeshadow(foreshadow);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Foreshadow> updateForeshadow(@PathVariable Long id, @RequestBody Foreshadow foreshadow) {
        try {
            return ResponseEntity.ok(foreshadowService.updateForeshadow(id, foreshadow));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteForeshadow(@PathVariable Long id) {
        foreshadowService.deleteForeshadow(id);
        return ResponseEntity.ok().build();
    }
}
