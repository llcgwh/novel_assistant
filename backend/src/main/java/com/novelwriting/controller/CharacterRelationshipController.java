package com.novelwriting.controller;

import com.novelwriting.entity.CharacterRelationship;
import com.novelwriting.service.CharacterRelationshipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/novels/{novelId}/relationships")
@CrossOrigin(origins = "*")
public class CharacterRelationshipController {

    @Autowired
    private CharacterRelationshipService relationshipService;

    @GetMapping
    public List<CharacterRelationship> getRelationshipsByNovelId(@PathVariable Long novelId) {
        return relationshipService.getRelationshipsByNovelId(novelId);
    }

    @GetMapping("/character/{characterId}")
    public List<CharacterRelationship> getRelationshipsByCharacterId(
            @PathVariable Long novelId, @PathVariable Long characterId) {
        return relationshipService.getRelationshipsByCharacterId(novelId, characterId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CharacterRelationship> getRelationshipById(
            @PathVariable Long novelId, @PathVariable Long id) {
        return relationshipService.getRelationshipById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public CharacterRelationship createRelationship(
            @PathVariable Long novelId, @RequestBody CharacterRelationship relationship) {
        relationship.setNovelId(novelId);
        return relationshipService.createRelationship(relationship);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CharacterRelationship> updateRelationship(
            @PathVariable Long novelId, @PathVariable Long id,
            @RequestBody CharacterRelationship relationship) {
        try {
            return ResponseEntity.ok(relationshipService.updateRelationship(id, relationship));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRelationship(@PathVariable Long novelId, @PathVariable Long id) {
        relationshipService.deleteRelationship(id);
        return ResponseEntity.ok().build();
    }
}
