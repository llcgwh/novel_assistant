package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    @Autowired
    private OutlineRepository outlineRepository;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    public Map<String, Object> globalSearch(Long novelId, String keyword) {
        Map<String, Object> results = new HashMap<>();

        results.put("characters", characterRepository.searchByNovelIdAndKeyword(novelId, keyword));
        results.put("scenes", sceneRepository.searchByNovelIdAndKeyword(novelId, keyword));
        results.put("foreshadows", foreshadowRepository.searchByNovelIdAndKeyword(novelId, keyword));
        results.put("outlines", outlineRepository.searchByNovelIdAndKeyword(novelId, keyword));
        results.put("timelineEvents", timelineEventRepository.searchByNovelIdAndKeyword(novelId, keyword));
        results.put("mapLocations", mapLocationRepository.searchByNovelIdAndKeyword(novelId, keyword));

        return results;
    }

    public Map<String, Object> searchByTag(Long novelId, Long tagId) {
        Map<String, Object> results = new HashMap<>();

        results.put("characters", characterRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("scenes", sceneRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("foreshadows", foreshadowRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("outlines", outlineRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("timelineEvents", timelineEventRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("mapLocations", mapLocationRepository.findByNovelIdAndTagId(novelId, tagId));

        return results;
    }
}
