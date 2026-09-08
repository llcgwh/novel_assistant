package com.novelwriting.service;

import com.novelwriting.entity.CharacterRelationship;
import com.novelwriting.repository.CharacterRelationshipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@org.springframework.transaction.annotation.Transactional
public class CharacterRelationshipService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private CharacterRelationshipRepository relationshipRepository;

    public List<CharacterRelationship> getRelationshipsByNovelId(Long novelId) {
        return relationshipRepository.findByNovelId(novelId);
    }

    public List<CharacterRelationship> getRelationshipsByCharacterId(Long novelId, Long characterId) {
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        return relationshipRepository.findByNovelIdAndCharacterId(novelId, characterId);
    }

    public Optional<CharacterRelationship> getRelationshipById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.CharacterRelationship.class, novelId, id);
        return relationshipRepository.findById(id);
    }

    public CharacterRelationship createRelationship(CharacterRelationship relationship) {
        Long novelId = relationship.getNovelId();
        scope.requireNovel(novelId);
        relationship.setId(null);
        scope.validateLinks(relationship, novelId, null);
        return relationshipRepository.save(relationship);
    }

    public CharacterRelationship updateRelationship(Long novelId, Long id, CharacterRelationship relationshipDetails) {
        scope.require(com.novelwriting.entity.CharacterRelationship.class, novelId, id);
        scope.validateLinks(relationshipDetails, novelId, id);
        CharacterRelationship relationship = relationshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Relationship not found"));

        relationship.setCharacterId1(relationshipDetails.getCharacterId1());
        relationship.setCharacterId2(relationshipDetails.getCharacterId2());
        relationship.setRelationshipType(relationshipDetails.getRelationshipType());
        relationship.setDescription(relationshipDetails.getDescription());

        return relationshipRepository.save(relationship);
    }

    public void deleteRelationship(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.CharacterRelationship.class, novelId, id);
        relationshipRepository.deleteById(id);
    }
}
