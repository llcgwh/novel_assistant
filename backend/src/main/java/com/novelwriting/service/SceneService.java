package com.novelwriting.service;

import com.novelwriting.entity.Scene;
import com.novelwriting.repository.SceneRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class SceneService {

    @Autowired
    private SceneRepository sceneRepository;

    public List<Scene> getAllScenes() {
        return sceneRepository.findAll();
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

        return sceneRepository.save(scene);
    }

    public void deleteScene(Long id) {
        sceneRepository.deleteById(id);
    }
}
