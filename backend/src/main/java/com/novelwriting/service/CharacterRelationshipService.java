package com.novelwriting.service;

import com.novelwriting.entity.CharacterRelationship;
import com.novelwriting.repository.CharacterRelationshipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class CharacterRelationshipService {

    @Autowired
    private CharacterRelationshipRepository relationshipRepository;

    public List<CharacterRelationship> getRelationshipsByNovelId(Long novelId) {
        return relationshipRepository.findByNovelId(novelId);
    }

    public List<CharacterRelationship> getRelationshipsByCharacterId(Long novelId, Long characterId) {
        return relationshipRepository.findByNovelIdAndCharacterId(novelId, characterId);
    }

    public Optional<CharacterRelationship> getRelationshipById(Long id) {
        return relationshipRepository.findById(id);
    }

    public CharacterRelationship createRelationship(CharacterRelationship relationship) {
        return relationshipRepository.save(relationship);
    }

    public CharacterRelationship updateRelationship(Long id, CharacterRelationship relationshipDetails) {
        CharacterRelationship relationship = relationshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Relationship not found"));

        relationship.setCharacterId1(relationshipDetails.getCharacterId1());
        relationship.setCharacterId2(relationshipDetails.getCharacterId2());
        relationship.setRelationshipType(relationshipDetails.getRelationshipType());
        relationship.setDescription(relationshipDetails.getDescription());

        return relationshipRepository.save(relationship);
    }

    public void deleteRelationship(Long id) {
        relationshipRepository.deleteById(id);
    }
}
