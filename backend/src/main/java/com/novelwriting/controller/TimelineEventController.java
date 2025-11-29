package com.novelwriting.controller;

import com.novelwriting.entity.TimelineEvent;
import com.novelwriting.service.TimelineEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/timeline-events")
@CrossOrigin(origins = "*")
public class TimelineEventController {

    @Autowired
    private TimelineEventService timelineEventService;

    @GetMapping
    public List<TimelineEvent> getAllTimelineEvents() {
        return timelineEventService.getAllTimelineEvents();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TimelineEvent> getTimelineEventById(@PathVariable Long id) {
        return timelineEventService.getTimelineEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public TimelineEvent createTimelineEvent(@RequestBody TimelineEvent timelineEvent) {
        return timelineEventService.createTimelineEvent(timelineEvent);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TimelineEvent> updateTimelineEvent(@PathVariable Long id, @RequestBody TimelineEvent timelineEvent) {
        try {
            return ResponseEntity.ok(timelineEventService.updateTimelineEvent(id, timelineEvent));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTimelineEvent(@PathVariable Long id) {
        timelineEventService.deleteTimelineEvent(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/characters")
    public ResponseEntity<TimelineEvent> addCharactersToEvent(@PathVariable Long id, @RequestBody Set<Long> characterIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addCharactersToEvent(id, characterIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/scenes")
    public ResponseEntity<TimelineEvent> addScenesToEvent(@PathVariable Long id, @RequestBody Set<Long> sceneIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addScenesToEvent(id, sceneIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/foreshadows")
    public ResponseEntity<TimelineEvent> addForeshadowsToEvent(@PathVariable Long id, @RequestBody Set<Long> foreshadowIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addForeshadowsToEvent(id, foreshadowIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/outlines")
    public ResponseEntity<TimelineEvent> addOutlinesToEvent(@PathVariable Long id, @RequestBody Set<Long> outlineIds) {
        try {
            return ResponseEntity.ok(timelineEventService.addOutlinesToEvent(id, outlineIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
