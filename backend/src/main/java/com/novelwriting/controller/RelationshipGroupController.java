package com.novelwriting.controller;

import com.novelwriting.entity.RelationshipGroup;
import com.novelwriting.service.RelationshipGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/relationship-groups")
@CrossOrigin(origins = "*")
public class RelationshipGroupController {

    @Autowired
    private RelationshipGroupService groupService;

    @GetMapping
    public List<RelationshipGroup> getGroupsByNovelId(@PathVariable Long novelId) {
        return groupService.getGroupsByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RelationshipGroup> getGroupById(
            @PathVariable Long novelId, @PathVariable Long id) {
        return groupService.getGroupById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public RelationshipGroup createGroup(
            @PathVariable Long novelId, @RequestBody CreateGroupRequest request) {
        RelationshipGroup group = new RelationshipGroup();
        group.setNovelId(novelId);
        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.setParentGroupId(request.getParentGroupId());

        RelationshipGroup saved = groupService.createGroup(group);

        if (request.getCharacterIds() != null && !request.getCharacterIds().isEmpty()) {
            groupService.setCharacters(saved.getId(), request.getCharacterIds());
            return groupService.getGroupById(saved.getId()).orElse(saved);
        }

        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<RelationshipGroup> updateGroup(
            @PathVariable Long novelId, @PathVariable Long id,
            @RequestBody CreateGroupRequest request) {
        try {
            RelationshipGroup details = new RelationshipGroup();
            details.setName(request.getName());
            details.setDescription(request.getDescription());
            details.setParentGroupId(request.getParentGroupId());

            RelationshipGroup updated = groupService.updateGroup(id, details);

            if (request.getCharacterIds() != null) {
                groupService.setCharacters(id, request.getCharacterIds());
                updated = groupService.getGroupById(id).orElse(updated);
            }

            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long novelId, @PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/characters")
    public ResponseEntity<RelationshipGroup> setCharacters(
            @PathVariable Long novelId, @PathVariable Long id,
            @RequestBody Set<Long> characterIds) {
        try {
            return ResponseEntity.ok(groupService.setCharacters(id, characterIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/characters/{characterId}")
    public ResponseEntity<RelationshipGroup> addCharacter(
            @PathVariable Long novelId, @PathVariable Long id,
            @PathVariable Long characterId) {
        try {
            return ResponseEntity.ok(groupService.addCharacter(id, characterId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/characters/{characterId}")
    public ResponseEntity<RelationshipGroup> removeCharacter(
            @PathVariable Long novelId, @PathVariable Long id,
            @PathVariable Long characterId) {
        try {
            return ResponseEntity.ok(groupService.removeCharacter(id, characterId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DTO for create/update requests
    static class CreateGroupRequest {
        private String name;
        private String description;
        private Long parentGroupId;
        private Set<Long> characterIds;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Long getParentGroupId() { return parentGroupId; }
        public void setParentGroupId(Long parentGroupId) { this.parentGroupId = parentGroupId; }

        public Set<Long> getCharacterIds() { return characterIds; }
        public void setCharacterIds(Set<Long> characterIds) { this.characterIds = characterIds; }
    }
}
