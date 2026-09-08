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
@org.springframework.transaction.annotation.Transactional
public class RelationshipGroupService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private RelationshipGroupRepository groupRepository;

    @Autowired
    private CharacterRepository characterRepository;

    public List<RelationshipGroup> getGroupsByNovelId(Long novelId) {
        return groupRepository.findByNovelId(novelId);
    }

    public Optional<RelationshipGroup> getGroupById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, id);
        return groupRepository.findById(id);
    }

    public RelationshipGroup createGroup(RelationshipGroup group) {
        Long novelId = group.getNovelId();
        scope.requireNovel(novelId);
        group.setId(null);
        scope.validateLinks(group, novelId, null);
        return groupRepository.save(group);
    }

    public RelationshipGroup updateGroup(Long novelId, Long id, RelationshipGroup groupDetails) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, id);
        scope.validateLinks(groupDetails, novelId, id);
        RelationshipGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        group.setName(groupDetails.getName());
        group.setDescription(groupDetails.getDescription());
        group.setParentGroupId(groupDetails.getParentGroupId());

        return groupRepository.save(group);
    }

    public void deleteGroup(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, id);
        groupRepository.deleteById(id);
    }

    @Transactional
    public RelationshipGroup setCharacters(Long novelId, Long groupId, Set<Long> characterIds) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, groupId);
        scope.requireAll(com.novelwriting.entity.Character.class, novelId, characterIds);
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
    public RelationshipGroup addCharacter(Long novelId, Long groupId, Long characterId) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, groupId);
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        RelationshipGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        characterRepository.findById(characterId).ifPresent(character -> {
            group.getCharacters().add(character);
        });

        return groupRepository.save(group);
    }

    @Transactional
    public RelationshipGroup removeCharacter(Long novelId, Long groupId, Long characterId) {
        scope.require(com.novelwriting.entity.RelationshipGroup.class, novelId, groupId);
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        RelationshipGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Relationship group not found"));

        group.getCharacters().removeIf(c -> c.getId().equals(characterId));

        return groupRepository.save(group);
    }
    public RelationshipGroup createGroupWithMembers(RelationshipGroup group, Set<Long> characterIds) {
        if (characterIds != null) scope.requireAll(Character.class, group.getNovelId(), characterIds);
        RelationshipGroup created = createGroup(group);
        return characterIds == null ? created : setCharacters(group.getNovelId(), created.getId(), characterIds);
    }

    public RelationshipGroup updateGroupWithMembers(Long novelId, Long id, RelationshipGroup details, Set<Long> characterIds) {
        scope.require(RelationshipGroup.class, novelId, id);
        if (characterIds != null) scope.requireAll(Character.class, novelId, characterIds);
        RelationshipGroup updated = updateGroup(novelId, id, details);
        return characterIds == null ? updated : setCharacters(novelId, id, characterIds);
    }
}
