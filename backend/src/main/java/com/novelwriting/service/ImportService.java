package com.novelwriting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ImportService {

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    @Autowired
    private OutlineRepository outlineRepository;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private CharacterRelationshipRepository relationshipRepository;

    @Autowired
    private WorldviewEntryRepository worldviewEntryRepository;

    @Autowired
    private RelationshipGroupRepository relationshipGroupRepository;

    @Transactional(rollbackFor = Exception.class)
    public void importFromJson(Long novelId, byte[] jsonData) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        JsonNode root = mapper.readTree(jsonData);
        BackupValidator.validate(root);
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new IllegalArgumentException("Novel not found"));

        Map<Long, Long> oldToNewTagIds = new HashMap<>();
        Map<Long, Long> oldToNewCharacterIds = new HashMap<>();
        Map<Long, Long> oldToNewSceneIds = new HashMap<>();
        Map<Long, Long> oldToNewForeshadowIds = new HashMap<>();
        Map<Long, Long> oldToNewOutlineIds = new HashMap<>();
        Map<Long, Long> oldToNewTimelineIds = new HashMap<>();
        Map<Long, Long> oldToNewMapLocationIds = new HashMap<>();
        Map<Long, Long> oldToNewWorldviewIds = new HashMap<>();

        // Step 1: Clear existing data for this novel (reverse dependency order)
        relationshipGroupRepository.deleteByNovelId(novelId);
        relationshipRepository.deleteByNovelId(novelId);
        worldviewEntryRepository.deleteByNovelId(novelId);
        timelineEventRepository.deleteByNovelId(novelId);
        foreshadowRepository.deleteByNovelId(novelId);
        outlineRepository.deleteByNovelId(novelId);
        sceneRepository.deleteByNovelId(novelId);
        mapLocationRepository.clearParentsByNovelId(novelId);
        mapLocationRepository.deleteByNovelId(novelId);
        characterRepository.deleteByNovelId(novelId);
        tagRepository.deleteByNovelId(novelId);


        // Step 2: Restore tags
        JsonNode tagsNode = root.get("tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tagNode : tagsNode) {
                Tag tag = new Tag();
                tag.setNovelId(novelId);
                tag.setName(tagNode.get("name").asText());
                tag.setColor(tagNode.has("color") ? tagNode.get("color").asText() : "#3498db");
                if (tagNode.has("description") && !tagNode.get("description").isNull()) {
                    tag.setDescription(tagNode.get("description").asText());
                }
                Tag saved = tagRepository.save(tag);
                if (tagNode.has("id")) {
                    oldToNewTagIds.put(tagNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 3: Restore characters
        JsonNode charsNode = root.get("characters");
        if (charsNode != null && charsNode.isArray()) {
            for (JsonNode charNode : charsNode) {
                Character c = new Character();
                c.setNovelId(novelId);
                c.setName(charNode.get("name").asText());
                setIfPresent(charNode, "description", v -> c.setDescription(v));
                setIfPresent(charNode, "personality", v -> c.setPersonality(v));
                setIfPresent(charNode, "appearance", v -> c.setAppearance(v));
                setIfPresent(charNode, "background", v -> c.setBackground(v));
                setIfPresent(charNode, "role", v -> c.setRole(v));
                setIfPresent(charNode, "portraitImage", v -> c.setPortraitImage(v));
                c.setTags(restoreTags(charNode, oldToNewTagIds));
                Character saved = characterRepository.save(c);
                if (charNode.has("id")) {
                    oldToNewCharacterIds.put(charNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 4: Restore scenes
        JsonNode scenesNode = root.get("scenes");
        if (scenesNode != null && scenesNode.isArray()) {
            for (JsonNode sceneNode : scenesNode) {
                Scene s = new Scene();
                s.setNovelId(novelId);
                s.setName(sceneNode.get("name").asText());
                setIfPresent(sceneNode, "description", v -> s.setDescription(v));
                setIfPresent(sceneNode, "location", v -> s.setLocation(v));
                setIfPresent(sceneNode, "atmosphere", v -> s.setAtmosphere(v));
                setIfPresent(sceneNode, "sceneImage", v -> s.setSceneImage(v));
                s.setTags(restoreTags(sceneNode, oldToNewTagIds));
                Scene saved = sceneRepository.save(s);
                if (sceneNode.has("id")) {
                    oldToNewSceneIds.put(sceneNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 5: Restore map locations (before scenes that reference them)
        JsonNode mapNode = root.get("mapLocations");
        if (mapNode != null && mapNode.isArray()) {
            for (JsonNode locNode : mapNode) {
                MapLocation ml = new MapLocation();
                ml.setNovelId(novelId);
                ml.setName(locNode.get("name").asText());
                setIfPresent(locNode, "description", v -> ml.setDescription(v));
                setIfPresentInt(locNode, "positionX", v -> ml.setPositionX(v));
                setIfPresentInt(locNode, "positionY", v -> ml.setPositionY(v));
                setIfPresent(locNode, "locationType", v -> ml.setLocationType(v));
                setIfPresent(locNode, "locationImage", v -> ml.setLocationImage(v));
                ml.setTags(restoreTags(locNode, oldToNewTagIds));
                MapLocation saved = mapLocationRepository.save(ml);
                if (locNode.has("id")) {
                    oldToNewMapLocationIds.put(locNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Remap parent_location_id for map locations
        if (mapNode != null && mapNode.isArray()) {
            for (JsonNode locNode : mapNode) {
                if (locNode.has("parentLocation") && !locNode.get("parentLocation").isNull()) {
                    Long oldId = locNode.get("id").asLong();
                    Long oldParentId = locNode.get("parentLocation").get("id").asLong();
                    Long newId = oldToNewMapLocationIds.get(oldId);
                    Long newParentId = oldToNewMapLocationIds.get(oldParentId);
                    if (newId != null && newParentId != null) {
                        MapLocation ml = mapLocationRepository.findById(newId).orElse(null);
                        MapLocation parent = mapLocationRepository.findById(newParentId).orElse(null);
                        if (ml != null && parent != null) {
                            ml.setParentLocation(parent);
                            mapLocationRepository.save(ml);
                        }
                    }
                }
            }
        }

        // Step 6: Restore foreshadows
        JsonNode foresNode = root.get("foreshadows");
        if (foresNode != null && foresNode.isArray()) {
            for (JsonNode fNode : foresNode) {
                Foreshadow f = new Foreshadow();
                f.setNovelId(novelId);
                f.setTitle(fNode.get("title").asText());
                setIfPresent(fNode, "content", v -> f.setContent(v));
                setIfPresent(fNode, "laidAt", v -> f.setLaidAt(v));
                setIfPresent(fNode, "revealedAt", v -> f.setRevealedAt(v));
                setIfPresent(fNode, "status", v -> f.setStatus(v));
                f.setTags(restoreTags(fNode, oldToNewTagIds));
                Foreshadow saved = foreshadowRepository.save(f);
                if (fNode.has("id")) {
                    oldToNewForeshadowIds.put(fNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 7: Restore outlines
        JsonNode outlinesNode = root.get("outlines");
        if (outlinesNode != null && outlinesNode.isArray()) {
            for (JsonNode oNode : outlinesNode) {
                Outline o = new Outline();
                o.setNovelId(novelId);
                o.setTitle(oNode.get("title").asText());
                setIfPresent(oNode, "content", v -> o.setContent(v));
                setIfPresentInt(oNode, "chapterNumber", v -> o.setChapterNumber(v));
                setIfPresentInt(oNode, "plotOrder", v -> o.setPlotOrder(v));
                setIfPresent(oNode, "status", v -> o.setStatus(v));
                o.setTags(restoreTags(oNode, oldToNewTagIds));
                Outline saved = outlineRepository.save(o);
                if (oNode.has("id")) {
                    oldToNewOutlineIds.put(oNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 8: Restore timeline events
        JsonNode timelineNode = root.get("timelineEvents");
        if (timelineNode != null && timelineNode.isArray()) {
            for (JsonNode tNode : timelineNode) {
                TimelineEvent te = new TimelineEvent();
                te.setNovelId(novelId);
                te.setTitle(tNode.get("title").asText());
                setIfPresent(tNode, "description", v -> te.setDescription(v));
                setIfPresent(tNode, "eventTime", v -> te.setEventTime(v));
                setIfPresentInt(tNode, "realOrder", v -> te.setRealOrder(v));
                te.setTags(restoreTags(tNode, oldToNewTagIds));
                TimelineEvent saved = timelineEventRepository.save(te);
                if (tNode.has("id")) {
                    oldToNewTimelineIds.put(tNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 9: Restore worldview entries
        JsonNode worldviewNode = root.get("worldviewEntries");
        if (worldviewNode != null && worldviewNode.isArray()) {
            for (JsonNode wNode : worldviewNode) {
                WorldviewEntry we = new WorldviewEntry();
                we.setNovelId(novelId);
                we.setName(wNode.get("name").asText());
                setIfPresent(wNode, "category", v -> we.setCategory(v));
                setIfPresent(wNode, "content", v -> we.setContent(v));
                setIfPresent(wNode, "entryImage", v -> we.setEntryImage(v));
                we.setTags(restoreTags(wNode, oldToNewTagIds));
                WorldviewEntry saved = worldviewEntryRepository.save(we);
                if (wNode.has("id")) {
                    oldToNewWorldviewIds.put(wNode.get("id").asLong(), saved.getId());
                }
            }
        }

        // Step 10: Restore character relationships
        JsonNode relsNode = root.get("characterRelationships");
        if (relsNode != null && relsNode.isArray()) {
            for (JsonNode rNode : relsNode) {
                CharacterRelationship cr = new CharacterRelationship();
                cr.setNovelId(novelId);
                Long oldChar1 = rNode.has("characterId1") ? rNode.get("characterId1").asLong() : null;
                Long oldChar2 = rNode.has("characterId2") ? rNode.get("characterId2").asLong() : null;
                if (oldChar1 != null && oldChar2 != null) {
                    Long newChar1 = oldToNewCharacterIds.get(oldChar1);
                    Long newChar2 = oldToNewCharacterIds.get(oldChar2);
                    if (newChar1 != null && newChar2 != null) {
                        cr.setCharacterId1(newChar1);
                        cr.setCharacterId2(newChar2);
                        setIfPresent(rNode, "relationshipType", v -> cr.setRelationshipType(v));
                        setIfPresent(rNode, "description", v -> cr.setDescription(v));
                        relationshipRepository.save(cr);
                    }
                }
            }
        }

        // Restore scene locations after every location has its new ID.
        for (JsonNode sceneNode : scenesNode) {
            Scene scene = sceneRepository.findById(oldToNewSceneIds.get(sceneNode.get("id").asLong())).orElseThrow();
            Set<MapLocation> locations = new HashSet<>();
            for (JsonNode location : sceneNode.path("mapLocations")) {
                locations.add(mapLocationRepository.findById(
                        oldToNewMapLocationIds.get(location.get("id").asLong())).orElseThrow());
            }
            scene.setMapLocations(locations);
            sceneRepository.save(scene);
        }

        // Two passes allow a child group to appear before its parent in the backup.
        Map<Long, RelationshipGroup> restoredGroups = new HashMap<>();
        for (JsonNode groupNode : root.path("relationshipGroups")) {
            RelationshipGroup group = new RelationshipGroup();
            group.setNovelId(novelId);
            group.setName(groupNode.get("name").asText());
            setIfPresent(groupNode, "description", group::setDescription);
            Set<Character> members = new HashSet<>();
            for (JsonNode member : groupNode.path("characters")) {
                members.add(characterRepository.findById(
                        oldToNewCharacterIds.get(member.get("id").asLong())).orElseThrow());
            }
            group.setCharacters(members);
            restoredGroups.put(groupNode.get("id").asLong(), relationshipGroupRepository.save(group));
        }
        for (JsonNode groupNode : root.path("relationshipGroups")) {
            if (groupNode.hasNonNull("parentGroupId")) {
                RelationshipGroup group = restoredGroups.get(groupNode.get("id").asLong());
                group.setParentGroupId(restoredGroups.get(groupNode.get("parentGroupId").asLong()).getId());
                relationshipGroupRepository.save(group);
            }
        }

        // Tag relations for timelines (characters/scenes/foreshadows/outlines)
        if (timelineNode != null && timelineNode.isArray()) {
            for (JsonNode tNode : timelineNode) {
                Long oldTimelineId = tNode.has("id") ? tNode.get("id").asLong() : null;
                Long newTimelineId = oldToNewTimelineIds.get(oldTimelineId);
                if (newTimelineId != null) {
                    TimelineEvent te = timelineEventRepository.findById(newTimelineId).orElse(null);
                    if (te != null) {
                        // Characters in timeline
                        if (tNode.has("characters") && tNode.get("characters").isArray()) {
                            Set<Character> chars = new HashSet<>();
                            for (JsonNode cNode : tNode.get("characters")) {
                                Long newId = oldToNewCharacterIds.get(cNode.get("id").asLong());
                                if (newId != null) characterRepository.findById(newId).ifPresent(chars::add);
                            }
                            te.setCharacters(chars);
                        }
                        // Scenes in timeline
                        if (tNode.has("scenes") && tNode.get("scenes").isArray()) {
                            Set<Scene> scenes = new HashSet<>();
                            for (JsonNode sNode : tNode.get("scenes")) {
                                Long newId = oldToNewSceneIds.get(sNode.get("id").asLong());
                                if (newId != null) sceneRepository.findById(newId).ifPresent(scenes::add);
                            }
                            te.setScenes(scenes);
                        }
                        // Foreshadows in timeline
                        if (tNode.has("foreshadows") && tNode.get("foreshadows").isArray()) {
                            Set<Foreshadow> fores = new HashSet<>();
                            for (JsonNode fNode : tNode.get("foreshadows")) {
                                Long newId = oldToNewForeshadowIds.get(fNode.get("id").asLong());
                                if (newId != null) foreshadowRepository.findById(newId).ifPresent(fores::add);
                            }
                            te.setForeshadows(fores);
                        }
                        // Outlines in timeline
                        if (tNode.has("outlines") && tNode.get("outlines").isArray()) {
                            Set<Outline> outlines = new HashSet<>();
                            for (JsonNode oNode : tNode.get("outlines")) {
                                Long newId = oldToNewOutlineIds.get(oNode.get("id").asLong());
                                if (newId != null) outlineRepository.findById(newId).ifPresent(outlines::add);
                            }
                            te.setOutlines(outlines);
                        }
                        timelineEventRepository.save(te);
                    }
                }
            }
        }

        // Worldview relation tags + character/scene/map relations
        if (worldviewNode != null && worldviewNode.isArray()) {
            for (JsonNode wNode : worldviewNode) {
                Long oldWorldviewId = wNode.has("id") ? wNode.get("id").asLong() : null;
                Long newWorldviewId = oldToNewWorldviewIds.get(oldWorldviewId);
                if (newWorldviewId != null) {
                    WorldviewEntry we = worldviewEntryRepository.findById(newWorldviewId).orElse(null);
                    if (we != null) {
                        if (wNode.has("characters") && wNode.get("characters").isArray()) {
                            Set<Character> chars = new HashSet<>();
                            for (JsonNode cNode : wNode.get("characters")) {
                                Long newId = oldToNewCharacterIds.get(cNode.get("id").asLong());
                                if (newId != null) characterRepository.findById(newId).ifPresent(chars::add);
                            }
                            we.setCharacters(chars);
                        }
                        if (wNode.has("scenes") && wNode.get("scenes").isArray()) {
                            Set<Scene> scenes = new HashSet<>();
                            for (JsonNode sNode : wNode.get("scenes")) {
                                Long newId = oldToNewSceneIds.get(sNode.get("id").asLong());
                                if (newId != null) sceneRepository.findById(newId).ifPresent(scenes::add);
                            }
                            we.setScenes(scenes);
                        }
                        if (wNode.has("mapLocations") && wNode.get("mapLocations").isArray()) {
                            Set<MapLocation> locs = new HashSet<>();
                            for (JsonNode lNode : wNode.get("mapLocations")) {
                                Long newId = oldToNewMapLocationIds.get(lNode.get("id").asLong());
                                if (newId != null) mapLocationRepository.findById(newId).ifPresent(locs::add);
                            }
                            we.setMapLocations(locs);
                        }
                        worldviewEntryRepository.save(we);
                    }
                }
            }
        }

        // Novel info update (keep existing, just update title/description if present)
        JsonNode novelNode = root.get("novel");
        if (novelNode != null) {
            setIfPresent(novelNode, "description", v -> novel.setDescription(v));
            setIfPresent(novelNode, "author", v -> novel.setAuthor(v));
            setIfPresent(novelNode, "genre", v -> novel.setGenre(v));
            novelRepository.save(novel);
        }
    }

    private Set<Tag> restoreTags(JsonNode node, Map<Long, Long> tagIds) {
        Set<Tag> tags = new HashSet<>();
        for (JsonNode tagNode : node.path("tags")) {
            tags.add(tagRepository.findById(tagIds.get(tagNode.get("id").asLong())).orElseThrow());
        }
        return tags;
    }

    private void setIfPresent(JsonNode node, String field, java.util.function.Consumer<String> setter) {
        if (node.has(field) && !node.get(field).isNull()) {
            setter.accept(node.get(field).asText());
        }
    }

    private void setIfPresentInt(JsonNode node, String field, java.util.function.Consumer<Integer> setter) {
        if (node.has(field) && !node.get(field).isNull()) {
            setter.accept(node.get(field).asInt());
        }
    }
}
