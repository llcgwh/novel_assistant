package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SearchService {

    @Autowired private WritingService writing;

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

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private WorldviewEntryRepository worldviewEntryRepository;

    public Map<String, Object> globalSearch(Long novelId, String keyword) {
        Map<String, Object> results = new HashMap<>();

        keyword = keyword.trim();
        var manuscripts=writing.search(novelId,keyword);
        manuscripts.forEach(row->((com.fasterxml.jackson.databind.node.ObjectNode)row).put("id",row.path("uid").asText()));
        results.put("manuscripts",manuscripts);
        results.put("worldviewEntries", worldviewEntryRepository.searchByNovelIdAndKeyword(novelId, keyword));
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

        results.put("worldviewEntries", worldviewEntryRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("characters", characterRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("scenes", sceneRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("foreshadows", foreshadowRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("outlines", outlineRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("timelineEvents", timelineEventRepository.findByNovelIdAndTagId(novelId, tagId));
        results.put("mapLocations", mapLocationRepository.findByNovelIdAndTagId(novelId, tagId));

        return results;
    }

    // 按标签名称模糊搜索（跨所有模块）
    public Map<String, Object> searchByTagName(Long novelId, String tagName) {
        Map<String, Object> results = new HashMap<>();

        // 先找到匹配的标签
        List<Tag> matchingTags = tagRepository.findByNovelIdAndNameContainingIgnoreCase(novelId, tagName);
        results.put("matchingTags", matchingTags);

        if (matchingTags.isEmpty()) {
            results.put("worldviewEntries", List.of());
            results.put("characters", List.of());
            results.put("scenes", List.of());
            results.put("foreshadows", List.of());
            results.put("outlines", List.of());
            results.put("timelineEvents", List.of());
            results.put("mapLocations", List.of());
            return results;
        }

        // 获取所有匹配标签的ID
        List<Long> tagIds = matchingTags.stream().map(Tag::getId).collect(Collectors.toList());

        // 搜索所有包含这些标签的元素
        results.put("worldviewEntries", worldviewEntryRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("characters", characterRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("scenes", sceneRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("foreshadows", foreshadowRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("outlines", outlineRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("timelineEvents", timelineEventRepository.findByNovelIdAndTagIds(novelId, tagIds));
        results.put("mapLocations", mapLocationRepository.findByNovelIdAndTagIds(novelId, tagIds));

        return results;
    }
}
