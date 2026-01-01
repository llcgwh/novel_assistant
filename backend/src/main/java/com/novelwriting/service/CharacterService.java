package com.novelwriting.service;

import com.novelwriting.entity.Character;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.CharacterRepository;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CharacterService {

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<Character> getAllCharacters() {
        return characterRepository.findAll();
    }

    public List<Character> getCharactersByNovelId(Long novelId) {
        return characterRepository.findByNovelId(novelId);
    }

    public Optional<Character> getCharacterById(Long id) {
        return characterRepository.findById(id);
    }

    public Character createCharacter(Character character) {
        return characterRepository.save(character);
    }

    public Character updateCharacter(Long id, Character characterDetails) {
        Character character = characterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Character not found"));

        character.setName(characterDetails.getName());
        character.setDescription(characterDetails.getDescription());
        character.setPersonality(characterDetails.getPersonality());
        character.setAppearance(characterDetails.getAppearance());
        character.setBackground(characterDetails.getBackground());
        character.setRole(characterDetails.getRole());
        character.setPortraitImage(characterDetails.getPortraitImage());

        return characterRepository.save(character);
    }

    public void deleteCharacter(Long id) {
        characterRepository.deleteById(id);
    }

    public List<Character> searchCharacters(Long novelId, String keyword) {
        return characterRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public Character addTagToCharacter(Long characterId, Long tagId) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        character.getTags().add(tag);
        return characterRepository.save(character);
    }

    public Character removeTagFromCharacter(Long characterId, Long tagId) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found"));

        character.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return characterRepository.save(character);
    }

    public Character setCharacterTags(Long characterId, Set<Long> tagIds) {
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            tagRepository.findById(tagId).ifPresent(tags::add);
        }
        character.setTags(tags);
        return characterRepository.save(character);
    }
}
