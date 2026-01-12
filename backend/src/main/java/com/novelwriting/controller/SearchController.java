package com.novelwriting.controller;

import com.novelwriting.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/novels/{novelId}/search")
@CrossOrigin(origins = "*")
public class SearchController {

    @Autowired
    private SearchService searchService;

    @GetMapping
    public Map<String, Object> globalSearch(
            @PathVariable Long novelId,
            @RequestParam String keyword) {
        return searchService.globalSearch(novelId, keyword);
    }

    @GetMapping("/tag/{tagId}")
    public Map<String, Object> searchByTag(
            @PathVariable Long novelId,
            @PathVariable Long tagId) {
        return searchService.searchByTag(novelId, tagId);
    }

    // 按标签名称模糊搜索（跨所有模块）
    @GetMapping("/tag-name")
    public Map<String, Object> searchByTagName(
            @PathVariable Long novelId,
            @RequestParam String tagName) {
        return searchService.searchByTagName(novelId, tagName);
    }
}
