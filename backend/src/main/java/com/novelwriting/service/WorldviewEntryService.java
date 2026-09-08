package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@org.springframework.transaction.annotation.Transactional
public class WorldviewEntryService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private WorldviewEntryRepository worldviewEntryRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    public List<WorldviewEntry> getEntriesByNovelId(Long novelId) {
        return worldviewEntryRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<WorldviewEntry> getEntryById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, id);
        return worldviewEntryRepository.findByIdWithTags(id);
    }

    public List<WorldviewEntry> getEntriesByCategory(Long novelId, String category) {
        return worldviewEntryRepository.findByNovelIdAndCategory(novelId, category);
    }

    public List<WorldviewEntry> searchEntries(Long novelId, String keyword) {
        return worldviewEntryRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public List<WorldviewEntry> searchEntries(Long novelId, String category, String keyword) {
        if (category != null && !category.isEmpty() && keyword != null && !keyword.isEmpty()) {
            return worldviewEntryRepository.searchByNovelIdAndCategoryAndKeyword(novelId, category, keyword);
        } else if (category != null && !category.isEmpty()) {
            return getEntriesByCategory(novelId, category);
        } else if (keyword != null && !keyword.isEmpty()) {
            return searchEntries(novelId, keyword);
        }
        return getEntriesByNovelId(novelId);
    }

    public WorldviewEntry createEntry(WorldviewEntry entry) {
        Long novelId = entry.getNovelId();
        scope.requireNovel(novelId);
        entry.setId(null);
        scope.validateLinks(entry, novelId, null);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry updateEntry(Long novelId, Long id, WorldviewEntry details) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, id);
        scope.validateLinks(details, novelId, id);
        WorldviewEntry entry = worldviewEntryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        entry.setName(details.getName());
        entry.setCategory(details.getCategory());
        entry.setContent(details.getContent());
        entry.setEntryImage(details.getEntryImage());

        return worldviewEntryRepository.save(entry);
    }

    public void deleteEntry(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, id);
        worldviewEntryRepository.deleteById(id);
    }

    // Tag management
    public WorldviewEntry setEntryTags(Long novelId, Long entryId, Set<Long> tagIds) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.requireAll(com.novelwriting.entity.Tag.class, novelId, tagIds);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        Set<Tag> tags = new HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found: " + tagId));
            tags.add(tag);
        }
        entry.setTags(tags);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry addTagToEntry(Long novelId, Long entryId, Long tagId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        entry.getTags().add(tag);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry removeTagFromEntry(Long novelId, Long entryId, Long tagId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        entry.getTags().removeIf(tag -> tag != null && tag.getId().equals(tagId));
        return worldviewEntryRepository.save(entry);
    }

    // Character relations
    public WorldviewEntry addCharacterRelation(Long novelId, Long entryId, Long characterId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));
        Character character = characterRepository.findById(characterId)
                .orElseThrow(() -> new RuntimeException("Character not found"));

        entry.getCharacters().add(character);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry removeCharacterRelation(Long novelId, Long entryId, Long characterId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        entry.getCharacters().removeIf(c -> c.getId().equals(characterId));
        return worldviewEntryRepository.save(entry);
    }

    // Scene relations
    public WorldviewEntry addSceneRelation(Long novelId, Long entryId, Long sceneId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        entry.getScenes().add(scene);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry removeSceneRelation(Long novelId, Long entryId, Long sceneId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        entry.getScenes().removeIf(s -> s.getId().equals(sceneId));
        return worldviewEntryRepository.save(entry);
    }

    // Map location relations
    public WorldviewEntry addMapLocationRelation(Long novelId, Long entryId, Long locationId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("MapLocation not found"));

        entry.getMapLocations().add(location);
        return worldviewEntryRepository.save(entry);
    }

    public WorldviewEntry removeMapLocationRelation(Long novelId, Long entryId, Long locationId) {
        scope.require(com.novelwriting.entity.WorldviewEntry.class, novelId, entryId);
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        WorldviewEntry entry = worldviewEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("WorldviewEntry not found"));

        entry.getMapLocations().removeIf(l -> l.getId().equals(locationId));
        return worldviewEntryRepository.save(entry);
    }
}
