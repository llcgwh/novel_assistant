package com.novelwriting.service;

import com.novelwriting.entity.Character;
import com.novelwriting.repository.CharacterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class CharacterService {

    @Autowired
    private CharacterRepository characterRepository;

    public List<Character> getAllCharacters() {
        return characterRepository.findAll();
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

        return characterRepository.save(character);
    }

    public void deleteCharacter(Long id) {
        characterRepository.deleteById(id);
    }
}
