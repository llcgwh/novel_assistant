package com.novelwriting.service;

import com.novelwriting.entity.TimelineEvent;
import com.novelwriting.entity.Character;
import com.novelwriting.entity.Scene;
import com.novelwriting.entity.Foreshadow;
import com.novelwriting.entity.Outline;
import com.novelwriting.repository.TimelineEventRepository;
import com.novelwriting.repository.CharacterRepository;
import com.novelwriting.repository.SceneRepository;
import com.novelwriting.repository.ForeshadowRepository;
import com.novelwriting.repository.OutlineRepository;
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

    public List<TimelineEvent> getAllTimelineEvents() {
        return timelineEventRepository.findAllByOrderByRealOrderAsc();
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

    public TimelineEvent addCharactersToEvent(Long eventId, Set<Long> characterIds) {
        TimelineEvent event = timelineEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Timeline Event not found"));

        Set<Character> characters = characterIds.stream()
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
}
