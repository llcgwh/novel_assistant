package com.novelwriting.controller;

import com.novelwriting.entity.Character;
import com.novelwriting.service.CharacterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/characters")
@CrossOrigin(origins = "*")
public class CharacterController {

    @Autowired
    private CharacterService characterService;

    @GetMapping
    public List<Character> getCharactersByNovelId(@PathVariable Long novelId) {
        return characterService.getCharactersByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Character> getCharacterById(@PathVariable Long novelId, @PathVariable Long id) {
        return characterService.getCharacterById(novelId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Character createCharacter(@PathVariable Long novelId, @RequestBody Character character) {
        character.setNovelId(novelId);
        return characterService.createCharacter(character);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Character> updateCharacter(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Character character) {
        return ResponseEntity.ok(characterService.updateCharacter(novelId, id, character));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCharacter(@PathVariable Long novelId, @PathVariable Long id) {
        characterService.deleteCharacter(novelId, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<Character> searchCharacters(@PathVariable Long novelId, @RequestParam String keyword) {
        return characterService.searchCharacters(novelId, keyword);
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Character> addTagToCharacter(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        return ResponseEntity.ok(characterService.addTagToCharacter(novelId, id, tagId));
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<Character> removeTagFromCharacter(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        return ResponseEntity.ok(characterService.removeTagFromCharacter(novelId, id, tagId));
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<Character> setCharacterTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        return ResponseEntity.ok(characterService.setCharacterTags(novelId, id, tagIds));
    }
}
