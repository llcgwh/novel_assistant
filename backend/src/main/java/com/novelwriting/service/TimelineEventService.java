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
public class TimelineEventService {

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

    public Optional<TimelineEvent> getTimelineEventById(Long id) {
        return timelineEventRepository.findById(id);
    }

    public TimelineEvent createTimelineEvent(TimelineEvent timelineEvent) {
        return timelineEventRepository.save(timelineEvent);
    }

    public TimelineEvent updateTimelineEvent(Long id, TimelineEvent eventDetails) {
        TimelineEvent event = timelineEventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.setTitle(eventDetails.getTitle());
        event.setDescription(eventDetails.getDescription());
        event.setEventTime(eventDetails.getEventTime());
        event.setRealOrder(eventDetails.getRealOrder());

        return timelineEventRepository.save(event);
    }

    public void deleteTimelineEvent(Long id) {
        timelineEventRepository.deleteById(id);
    }

    public List<TimelineEvent> searchTimelineEvents(Long novelId, String keyword) {
        return timelineEventRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public TimelineEvent addCharactersToEvent(Long eventId, Set<Long> characterIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<com.novelwriting.entity.Character> characters = characterIds.stream()
                .map(id -> characterRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Character not found: " + id)))
                .collect(Collectors.toSet());

        event.getCharacters().addAll(characters);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addScenesToEvent(Long eventId, Set<Long> sceneIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Scene> scenes = sceneIds.stream()
                .map(id -> sceneRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Scene not found: " + id)))
                .collect(Collectors.toSet());

        event.getScenes().addAll(scenes);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addForeshadowsToEvent(Long eventId, Set<Long> foreshadowIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Foreshadow> foreshadows = foreshadowIds.stream()
                .map(id -> foreshadowRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Foreshadow not found: " + id)))
                .collect(Collectors.toSet());

        event.getForeshadows().addAll(foreshadows);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addOutlinesToEvent(Long eventId, Set<Long> outlineIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Outline> outlines = outlineIds.stream()
                .map(id -> outlineRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Outline not found: " + id)))
                .collect(Collectors.toSet());

        event.getOutlines().addAll(outlines);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeCharactersFromEvent(Long eventId, Long characterId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getCharacters().removeIf(character -> character.getId().equals(characterId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeScenesFromEvent(Long eventId, Long sceneId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getScenes().removeIf(scene -> scene.getId().equals(sceneId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeForeshadowsFromEvent(Long eventId, Long foreshadowId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getForeshadows().removeIf(foreshadow -> foreshadow.getId().equals(foreshadowId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeOutlinesFromEvent(Long eventId, Long outlineId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getOutlines().removeIf(outline -> outline.getId().equals(outlineId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent addTagToEvent(Long eventId, Long tagId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        event.getTags().add(tag);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent removeTagFromEvent(Long eventId, Long tagId) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        event.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return timelineEventRepository.save(event);
    }

    public TimelineEvent setEventTags(Long eventId, Set<Long> tagIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            tagRepository.findById(tagId).ifPresent(tags::add);
        }
        event.setTags(tags);
        return timelineEventRepository.save(event);
    }

    public TimelineEvent updateEventRelations(Long eventId, Set<Long> characterIds,
            Set<Long> sceneIds, Set<Long> foreshadowIds, Set<Long> outlineIds) {
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
