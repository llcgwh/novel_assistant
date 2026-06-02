package com.novelwriting.service;

import com.novelwriting.entity.MapLocation;
import com.novelwriting.entity.Scene;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.MapLocationRepository;
import com.novelwriting.repository.SceneRepository;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class SceneService {

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    public List<Scene> getAllScenes() {
        return sceneRepository.findAll();
    }

    public List<Scene> getScenesByNovelId(Long novelId) {
        return sceneRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<Scene> getSceneById(Long id) {
        return sceneRepository.findById(id);
    }

    public Scene createScene(Scene scene) {
        return sceneRepository.save(scene);
    }

    public Scene updateScene(Long id, Scene sceneDetails) {
        Scene scene = sceneRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.setName(sceneDetails.getName());
        scene.setDescription(sceneDetails.getDescription());
        scene.setMapLocation(sceneDetails.getMapLocation());
        scene.setAtmosphere(sceneDetails.getAtmosphere());
        scene.setSceneImage(sceneDetails.getSceneImage());

        return sceneRepository.save(scene);
    }

    public void deleteScene(Long id) {
        sceneRepository.deleteById(id);
    }

    public List<Scene> searchScenes(Long novelId, String keyword) {
        return sceneRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public Scene addTagToScene(Long sceneId, Long tagId) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        scene.getTags().add(tag);
        return sceneRepository.save(scene);
    }

    public Scene removeTagFromScene(Long sceneId, Long tagId) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return sceneRepository.save(scene);
    }

    public Scene setSceneTags(Long sceneId, Set<Long> tagIds) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found: " + tagId));
            tags.add(tag);
        }
        scene.setTags(tags);
        return sceneRepository.save(scene);
    }

    public Scene addMapLocationToScene(Long sceneId, Long locationId) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("MapLocation not found"));

        scene.getMapLocations().add(location);
        return sceneRepository.save(scene);
    }

    public Scene removeMapLocationFromScene(Long sceneId, Long locationId) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.getMapLocations().removeIf(loc -> loc.getId().equals(locationId));
        return sceneRepository.save(scene);
    }

    public Scene setSceneMapLocations(Long sceneId, Set<Long> locationIds) {
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        Set<MapLocation> locations = new java.util.HashSet<>();
        for (Long locationId : locationIds) {
            MapLocation location = mapLocationRepository.findById(locationId)
                    .orElseThrow(() -> new RuntimeException("MapLocation not found: " + locationId));
            locations.add(location);
        }
        scene.setMapLocations(locations);
        return sceneRepository.save(scene);
    }
}
