package com.novelwriting.service;

import com.novelwriting.entity.Scene;
import com.novelwriting.entity.Tag;
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

    public List<Scene> getAllScenes() {
        return sceneRepository.findAll();
    }

    public List<Scene> getScenesByNovelId(Long novelId) {
        return sceneRepository.findByNovelId(novelId);
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
        scene.setLocation(sceneDetails.getLocation());
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
            tagRepository.findById(tagId).ifPresent(tags::add);
        }
        scene.setTags(tags);
        return sceneRepository.save(scene);
    }
}
