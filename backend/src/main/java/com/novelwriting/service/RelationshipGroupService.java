package com.novelwriting.service;

import com.novelwriting.entity.Character;
import com.novelwriting.entity.RelationshipGroup;
import com.novelwriting.repository.CharacterRepository;
import com.novelwriting.repository.RelationshipGroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RelationshipGroupService {

    @Autowired
    private RelationshipGroupRepository groupRepository;

    @Autowired
    private CharacterRepository characterRepository;

    public List<RelationshipGroup> getGroupsByNovelId(Long novelId) {
        return groupRepository.findByNovelId(novelId);
    }

    public Optional<RelationshipGroup> getGroupById(Long id) {
        return groupRepository.findById(id);
    }

    public RelationshipGroup createGroup(RelationshipGroup group) {
        return groupRepository.save(group);
    }

    public RelationshipGroup updateGroup(Long id, RelationshipGroup groupDetails) {
        RelationshipGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        group.setName(groupDetails.getName());
        group.setDescription(groupDetails.getDescription());
        group.setParentGroupId(groupDetails.getParentGroupId());

        return groupRepository.save(group);
    }

    public void deleteGroup(Long id) {
        groupRepository.deleteById(id);
    }

    @Transactional
    public RelationshipGroup setCharacters(Long groupId, Set<Long> characterIds) {
        RelationshipGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        Set<Character> characters = new HashSet<>();
        for (Long charId : characterIds) {
            characterRepository.findById(charId).ifPresent(characters::add);
        }
        group.setCharacters(characters);

        return groupRepository.save(group);
    }

    @Transactional
    public RelationshipGroup addCharacter(Long groupId, Long characterId) {
        RelationshipGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        characterRepository.findById(characterId).ifPresent(character -> {
            group.getCharacters().add(character);
        });

        return groupRepository.save(group);
    }

    @Transactional
    public RelationshipGroup removeCharacter(Long groupId, Long characterId) {
        RelationshipGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        group.getCharacters().removeIf(c -> c.getId().equals(characterId));

        return groupRepository.save(group);
    }
}
