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
@org.springframework.transaction.annotation.Transactional
public class SceneService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    public List<Scene> getScenesByNovelId(Long novelId) {
        return sceneRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<Scene> getSceneById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, id);
        return sceneRepository.findById(id);
    }

    public Scene createScene(Scene scene) {
        Long novelId = scene.getNovelId();
        scope.requireNovel(novelId);
        scene.setId(null);
        scope.validateLinks(scene, novelId, null);
        return sceneRepository.save(scene);
    }

    public Scene updateScene(Long novelId, Long id, Scene sceneDetails) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, id);
        scope.validateLinks(sceneDetails, novelId, id);
        Scene scene = sceneRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.setName(sceneDetails.getName());
        scene.setDescription(sceneDetails.getDescription());
//        scene.setMapLocation(sceneDetails.getMapLocation());
        scene.setAtmosphere(sceneDetails.getAtmosphere());
        scene.setSceneImage(sceneDetails.getSceneImage());

        return sceneRepository.save(scene);
    }

    public void deleteScene(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, id);
        sceneRepository.deleteById(id);
    }

    public List<Scene> searchScenes(Long novelId, String keyword) {
        return sceneRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public Scene addTagToScene(Long novelId, Long sceneId, Long tagId) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        scene.getTags().add(tag);
        return sceneRepository.save(scene);
    }

    public Scene removeTagFromScene(Long novelId, Long sceneId, Long tagId) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return sceneRepository.save(scene);
    }

    public Scene setSceneTags(Long novelId, Long sceneId, Set<Long> tagIds) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.requireAll(com.novelwriting.entity.Tag.class, novelId, tagIds);
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

    public Scene addMapLocationToScene(Long novelId, Long sceneId, Long locationId) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("MapLocation not found"));

        scene.getMapLocations().add(location);
        return sceneRepository.save(scene);
    }

    public Scene removeMapLocationFromScene(Long novelId, Long sceneId, Long locationId) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        Scene scene = sceneRepository.findById(sceneId)
                .orElseThrow(() -> new RuntimeException("Scene not found"));

        scene.getMapLocations().removeIf(loc -> loc.getId().equals(locationId));
        return sceneRepository.save(scene);
    }

    public Scene setSceneMapLocations(Long novelId, Long sceneId, Set<Long> locationIds) {
        scope.require(com.novelwriting.entity.Scene.class, novelId, sceneId);
        scope.requireAll(com.novelwriting.entity.MapLocation.class, novelId, locationIds);
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
