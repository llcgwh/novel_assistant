package com.novelwriting.controller;

import com.novelwriting.entity.Character;
import com.novelwriting.entity.CharacterRelationship;
import com.novelwriting.service.CharacterService;
import com.novelwriting.service.CharacterRelationshipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/novels/{novelId}/visualization")
@CrossOrigin(origins = "*")
public class VisualizationController {

    @Autowired
    private CharacterService characterService;

    @Autowired
    private CharacterRelationshipService relationshipService;

    @GetMapping("/character-network")
    public Map<String, Object> getCharacterNetwork(@PathVariable Long novelId) {
        List<Character> characters = characterService.getCharactersByNovelId(novelId);
        List<CharacterRelationship> relationships = relationshipService.getRelationshipsByNovelId(novelId);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Character c : characters) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", c.getId());
            node.put("name", c.getName());
            node.put("role", c.getRole());
            node.put("portraitImage", c.getPortraitImage());
            nodes.add(node);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (CharacterRelationship r : relationships) {
            Map<String, Object> edge = new HashMap<>();
            edge.put("id", r.getId());
            edge.put("source", r.getCharacterId1());
            edge.put("target", r.getCharacterId2());
            edge.put("relationshipType", r.getRelationshipType());
            edge.put("description", r.getDescription());
            edges.add(edge);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);

        return result;
    }
}
