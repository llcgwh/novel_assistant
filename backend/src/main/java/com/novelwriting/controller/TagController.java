package com.novelwriting.controller;

import com.novelwriting.entity.Tag;
import com.novelwriting.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/novels/{novelId}/tags")
@CrossOrigin(origins = "*")
public class TagController {

    @Autowired
    private TagService tagService;

    @GetMapping
    public List<Tag> getTagsByNovelId(@PathVariable Long novelId) {
        return tagService.getTagsByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tag> getTagById(@PathVariable Long novelId, @PathVariable Long id) {
        return tagService.getTagById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Tag createTag(@PathVariable Long novelId, @RequestBody Tag tag) {
        tag.setNovelId(novelId);
        return tagService.createTag(tag);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tag> updateTag(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Tag tag) {
        try {
            return ResponseEntity.ok(tagService.updateTag(id, tag));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable Long novelId, @PathVariable Long id) {
        tagService.deleteTag(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Tag> searchTags(@PathVariable Long novelId, @RequestParam String name) {
        return tagService.searchTags(novelId, name);
    }
}
