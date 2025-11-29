package com.novelwriting.controller;

import com.novelwriting.entity.Outline;
import com.novelwriting.service.OutlineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/outlines")
@CrossOrigin(origins = "*")
public class OutlineController {

    @Autowired
    private OutlineService outlineService;

    @GetMapping
    public List<Outline> getAllOutlines() {
        return outlineService.getAllOutlines();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Outline> getOutlineById(@PathVariable Long id) {
        return outlineService.getOutlineById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Outline createOutline(@RequestBody Outline outline) {
        return outlineService.createOutline(outline);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Outline> updateOutline(@PathVariable Long id, @RequestBody Outline outline) {
        try {
            return ResponseEntity.ok(outlineService.updateOutline(id, outline));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOutline(@PathVariable Long id) {
        outlineService.deleteOutline(id);
        return ResponseEntity.ok().build();
    }
}
