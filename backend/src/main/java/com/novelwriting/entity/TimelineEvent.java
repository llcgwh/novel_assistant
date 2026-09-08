package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "timeline_events")
public class TimelineEvent implements NovelOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id")
    private Long novelId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "event_time", length = 100)
    private String eventTime;

    @Column(name = "real_order")
    private Integer realOrder;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToMany
    @JoinTable(
        name = "timeline_event_characters",
        joinColumns = @JoinColumn(name = "timeline_event_id"),
        inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    private Set<Character> characters = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "timeline_event_scenes",
        joinColumns = @JoinColumn(name = "timeline_event_id"),
        inverseJoinColumns = @JoinColumn(name = "scene_id")
    )
    private Set<Scene> scenes = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "timeline_event_foreshadows",
        joinColumns = @JoinColumn(name = "timeline_event_id"),
        inverseJoinColumns = @JoinColumn(name = "foreshadow_id")
    )
    private Set<Foreshadow> foreshadows = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "timeline_event_outlines",
        joinColumns = @JoinColumn(name = "timeline_event_id"),
        inverseJoinColumns = @JoinColumn(name = "outline_id")
    )
    private Set<Outline> outlines = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "timeline_event_tags",
        joinColumns = @JoinColumn(name = "timeline_event_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
