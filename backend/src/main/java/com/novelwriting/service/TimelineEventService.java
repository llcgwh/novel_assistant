package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@org.springframework.transaction.annotation.Transactional
public class TimelineEventService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    @Autowired
    private OutlineRepository outlineRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<TimelineEvent> getAllTimelineEvents() {
        return timelineEventRepository.findAllByOrderByRealOrderAsc();
    }

    public List<TimelineEvent> getTimelineEventsByNovelId(Long novelId) {
        return timelineEventRepository.findByNovelIdWithRelations(novelId);
    }

    public Optional<TimelineEvent> getTimelineEventById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, id);
        return timelineEventRepository.findById(id);
    }

    public TimelineEvent createTimelineEvent(TimelineEvent timelineEvent) {
        Long novelId = timelineEvent.getNovelId();
        scope.requireNovel(novelId);
        timelineEvent.setId(null);
        scope.validateLinks(timelineEvent, novelId, null);
        return timelineEventRepository.save(timelineEvent);
    }

    public TimelineEvent updateTimelineEvent(Long novelId, Long id, TimelineEvent eventDetails) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, id);
        scope.validateLinks(eventDetails, novelId, id);
        TimelineEvent event = timelineEventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.setTitle(eventDetails.getTitle());
        event.setDescription(eventDetails.getDescription());
        event.setEventTime(eventDetails.getEventTime());
        event.setRealOrder(eventDetails.getRealOrder());

        return timelineEventRepository.save(event);
    }

    public void deleteTimelineEvent(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, id);
        timelineEventRepository.deleteById(id);
    }

    public List<TimelineEvent> searchTimelineEvents(Long novelId, String keyword) {
        return timelineEventRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public TimelineEvent addCharactersToEvent(Long novelId, Long eventId, Set<Long> characterIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Character.class, novelId, characterIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<com.novelwriting.entity.Character> characters = characterIds.stream()
                .map(id -> characterRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Character not found: " + id)))
                .collect(Collectors.toSet());

        event.getCharacters().addAll(characters);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addScenesToEvent(Long novelId, Long eventId, Set<Long> sceneIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Scene.class, novelId, sceneIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Scene> scenes = sceneIds.stream()
                .map(id -> sceneRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Scene not found: " + id)))
                .collect(Collectors.toSet());

        event.getScenes().addAll(scenes);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addForeshadowsToEvent(Long novelId, Long eventId, Set<Long> foreshadowIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Foreshadow> foreshadows = foreshadowIds.stream()
                .map(id -> foreshadowRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Foreshadow not found: " + id)))
                .collect(Collectors.toSet());

        event.getForeshadows().addAll(foreshadows);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addOutlinesToEvent(Long novelId, Long eventId, Set<Long> outlineIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Outline.class, novelId, outlineIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Outline> outlines = outlineIds.stream()
                .map(id -> outlineRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Outline not found: " + id)))
                .collect(Collectors.toSet());

        event.getOutlines().addAll(outlines);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeCharactersFromEvent(Long novelId, Long eventId, Long characterId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Character.class, novelId, characterId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getCharacters().removeIf(character -> character.getId().equals(characterId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeScenesFromEvent(Long novelId, Long eventId, Long sceneId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getScenes().removeIf(scene -> scene.getId().equals(sceneId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeForeshadowsFromEvent(Long novelId, Long eventId, Long foreshadowId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getForeshadows().removeIf(foreshadow -> foreshadow.getId().equals(foreshadowId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeOutlinesFromEvent(Long novelId, Long eventId, Long outlineId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Outline.class, novelId, outlineId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getOutlines().removeIf(outline -> outline.getId().equals(outlineId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addTagToEvent(Long novelId, Long eventId, Long tagId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        event.getTags().add(tag);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeTagFromEvent(Long novelId, Long eventId, Long tagId) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent setEventTags(Long novelId, Long eventId, Set<Long> tagIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Tag.class, novelId, tagIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            tagRepository.findById(tagId).ifPresent(tags::add);
        }
        event.setTags(tags);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent updateEventRelations(Long novelId, Long eventId, Set<Long> characterIds, Set<Long> sceneIds, Set<Long> foreshadowIds, Set<Long> outlineIds) {
        scope.require(com.novelwriting.entity.TimelineEvent.class, novelId, eventId);
        scope.requireAll(com.novelwriting.entity.Character.class, novelId, characterIds);
        scope.requireAll(com.novelwriting.entity.Scene.class, novelId, sceneIds);
        scope.requireAll(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowIds);
        scope.requireAll(com.novelwriting.entity.Outline.class, novelId, outlineIds);
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        // Clear existing relations
        event.getCharacters().clear();
        event.getScenes().clear();
        event.getForeshadows().clear();
        event.getOutlines().clear();

        // Set new character relations
        for (Long characterId : characterIds) {
            characterRepository.findById(characterId)
                    .ifPresent(character -> event.getCharacters().add(character));
        }

        // Set new scene relations
        for (Long sceneId : sceneIds) {
            sceneRepository.findById(sceneId)
                    .ifPresent(scene -> event.getScenes().add(scene));
        }

        // Set new foreshadow relations
        for (Long foreshadowId : foreshadowIds) {
            foreshadowRepository.findById(foreshadowId)
                    .ifPresent(foreshadow -> event.getForeshadows().add(foreshadow));
        }

        // Set new outline relations
        for (Long outlineId : outlineIds) {
            outlineRepository.findById(outlineId)
                    .ifPresent(outline -> event.getOutlines().add(outline));
        }

        return timelineEventRepository.save(event);
    }
}
