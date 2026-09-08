package com.novelwriting.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "worldview_entries")
public class WorldviewEntry implements NovelOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id")
    private Long novelId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "entry_image", length = 500)
    private String entryImage;

    @ManyToMany
    @JoinTable(
        name = "worldview_entry_tags",
        joinColumns = @JoinColumn(name = "worldview_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "worldview_entry_characters",
        joinColumns = @JoinColumn(name = "worldview_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "character_id")
    )
    private Set<Character> characters = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "worldview_entry_scenes",
        joinColumns = @JoinColumn(name = "worldview_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "scene_id")
    )
    private Set<Scene> scenes = new HashSet<>();

    @ManyToMany
    @JoinTable(
        name = "worldview_entry_map_locations",
        joinColumns = @JoinColumn(name = "worldview_entry_id"),
        inverseJoinColumns = @JoinColumn(name = "map_location_id")
    )
    private Set<MapLocation> mapLocations = new HashSet<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
