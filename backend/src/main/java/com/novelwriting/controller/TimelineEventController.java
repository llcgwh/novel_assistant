package com.novelwriting.controller;

import com.novelwriting.entity.TimelineEvent;
import com.novelwriting.service.TimelineEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/novels/{novelId}/timeline-events")
@CrossOrigin(origins = "*")
public class TimelineEventController {

    @Autowired
    private TimelineEventService timelineEventService;

    @GetMapping
    public List<TimelineEvent> getTimelineEventsByNovelId(@PathVariable Long novelId) {
        return timelineEventService.getTimelineEventsByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TimelineEvent> getTimelineEventById(@PathVariable Long novelId, @PathVariable Long id) {
        return timelineEventService.getTimelineEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public TimelineEvent createTimelineEvent(@PathVariable Long novelId, @RequestBody TimelineEvent event) {
        event.setNovelId(novelId);
        return timelineEventService.createTimelineEvent(event);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TimelineEvent> updateTimelineEvent(@PathVariable Long novelId, @PathVariable Long id, @RequestBody TimelineEvent event) {
        try {
            return ResponseEntity.ok(timelineEventService.updateTimelineEvent(id, event));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTimelineEvent(@PathVariable Long novelId, @PathVariable Long id) {
        timelineEventService.deleteTimelineEvent(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<TimelineEvent> searchTimelineEvents(@PathVariable Long novelId, @RequestParam String keyword) {
        return timelineEventService.searchTimelineEvents(novelId, keyword);
    }

    @PostMapping("/{id}/characters")
    public ResponseEntity<TimelineEvent> addCharactersToEvent(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> characterIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addCharactersToEvent(id, characterIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/scenes")
    public ResponseEntity<TimelineEvent> addScenesToEvent(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> sceneIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addScenesToEvent(id, sceneIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/foreshadows")
    public ResponseEntity<TimelineEvent> addForeshadowsToEvent(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> foreshadowIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addForeshadowsToEvent(id, foreshadowIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/outlines")
    public ResponseEntity<TimelineEvent> addOutlinesToEvent(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> outlineIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addOutlinesToEvent(id, outlineIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/characters/{characterId}")
    public ResponseEntity<TimelineEvent> removeCharacterFromEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long characterId) {
        try {
            return ResponseEntity.ok(timelineEventService.removeCharactersFromEvent(id, characterId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/scenes/{sceneId}")
    public ResponseEntity<TimelineEvent> removeSceneFromEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long sceneId) {
        try {
            return ResponseEntity.ok(timelineEventService.removeScenesFromEvent(id, sceneId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/foreshadows/{foreshadowId}")
    public ResponseEntity<TimelineEvent> removeForeshadowFromEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long foreshadowId) {
        try {
            return ResponseEntity.ok(timelineEventService.removeForeshadowsFromEvent(id, foreshadowId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/outlines/{outlineId}")
    public ResponseEntity<TimelineEvent> removeOutlineFromEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long outlineId) {
        try {
            return ResponseEntity.ok(timelineEventService.removeOutlinesFromEvent(id, outlineId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/relations")
    public ResponseEntity<TimelineEvent> updateEventRelations(
            @PathVariable Long novelId, @PathVariable Long id,
            @RequestBody Map<String, Object> relations) {
        try {
            @SuppressWarnings("unchecked")
            Set<Long> characterIds = new java.util.HashSet<>(
                    ((java.util.List<Integer>) relations.getOrDefault("characterIds", java.util.List.of())).stream()
                            .map(Long::valueOf).toList());
            @SuppressWarnings("unchecked")
            Set<Long> sceneIds = new java.util.HashSet<>(
                    ((java.util.List<Integer>) relations.getOrDefault("sceneIds", java.util.List.of())).stream()
                            .map(Long::valueOf).toList());
            @SuppressWarnings("unchecked")
            Set<Long> foreshadowIds = new java.util.HashSet<>(
                    ((java.util.List<Integer>) relations.getOrDefault("foreshadowIds", java.util.List.of())).stream()
                            .map(Long::valueOf).toList());
            @SuppressWarnings("unchecked")
            Set<Long> outlineIds = new java.util.HashSet<>(
                    ((java.util.List<Integer>) relations.getOrDefault("outlineIds", java.util.List.of())).stream()
                            .map(Long::valueOf).toList());

            return ResponseEntity.ok(timelineEventService.updateEventRelations(
                    id, characterIds, sceneIds, foreshadowIds, outlineIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<TimelineEvent> addTagToEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(timelineEventService.addTagToEvent(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<TimelineEvent> removeTagFromEvent(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(timelineEventService.removeTagFromEvent(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<TimelineEvent> setEventTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(timelineEventService.setEventTags(id, tagIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
