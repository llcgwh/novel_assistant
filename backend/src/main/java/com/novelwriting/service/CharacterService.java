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
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CharacterService {

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<Character> getAllCharacters() {
        return characterRepository.findAll();
    }

    public List<Character> getCharactersByNovelId(Long novelId) {
        return characterRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<Character> getCharacterById(Long novelId, Long id) {
        return characterRepository.findById(id)
                .filter(character -> Objects.equals(character.getNovelId(), novelId));
    }

    public Character createCharacter(Character character) {
        character.setId(null);
        character.setTags(validateTags(character.getNovelId(), character.getTags()));
        return characterRepository.save(character);
    }

    public Character updateCharacter(Long novelId, Long id, Character characterDetails) {
        Character character = requireCharacter(novelId, id);

        character.setName(characterDetails.getName());
        character.setDescription(characterDetails.getDescription());
        character.setPersonality(characterDetails.getPersonality());
        character.setAppearance(characterDetails.getAppearance());
        character.setBackground(characterDetails.getBackground());
        character.setRole(characterDetails.getRole());
        character.setPortraitImage(characterDetails.getPortraitImage());

        return characterRepository.save(character);
    }

    public void deleteCharacter(Long novelId, Long id) {
        characterRepository.delete(requireCharacter(novelId, id));
    }

    public List<Character> searchCharacters(Long novelId, String keyword) {
        return characterRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public Character addTagToCharacter(Long novelId, Long characterId, Long tagId) {
        Character character = requireCharacter(novelId, characterId);
        Tag tag = requireTag(novelId, tagId);

        character.getTags().add(tag);
        return characterRepository.save(character);
    }

    public Character removeTagFromCharacter(Long novelId, Long characterId, Long tagId) {
        Character character = requireCharacter(novelId, characterId);

        requireTag(novelId, tagId);
        character.getTags().removeIf(tag -> tag != null && tag.getId().equals(tagId));
        return characterRepository.save(character);
    }

    public Character setCharacterTags(Long novelId, Long characterId, Set<Long> tagIds) {
        Character character = requireCharacter(novelId, characterId);

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = requireTag(novelId, tagId);
            tags.add(tag);
        }
        character.setTags(tags);
        return characterRepository.save(character);
    }

    private Character requireCharacter(Long novelId, Long id) {
        return getCharacterById(novelId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found"));
    }

    private Tag requireTag(Long novelId, Long tagId) {
        if (tagId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag ID is required");
        }
        return tagRepository.findById(tagId)
                .filter(tag -> Objects.equals(tag.getNovelId(), novelId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found"));
    }

    private Set<Tag> validateTags(Long novelId, Set<Tag> tags) {
        Set<Tag> validated = new java.util.HashSet<>();
        if (tags != null) {
            for (Tag tag : tags) {
                validated.add(requireTag(novelId, tag == null ? null : tag.getId()));
            }
        }
        return validated;
    }

}
