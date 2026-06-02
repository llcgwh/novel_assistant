package com.novelwriting.service;

import com.novelwriting.entity.Outline;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.OutlineRepository;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class OutlineService {

    @Autowired
    private OutlineRepository outlineRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<Outline> getAllOutlines() {
        return outlineRepository.findAll();
    }

    public List<Outline> getOutlinesByNovelId(Long novelId) {
        return outlineRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<Outline> getOutlineById(Long id) {
        return outlineRepository.findById(id);
    }

    public Outline createOutline(Outline outline) {
        return outlineRepository.save(outline);
    }

    public Outline updateOutline(Long id, Outline outlineDetails) {
        Outline outline = outlineRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Outline not found"));

        outline.setTitle(outlineDetails.getTitle());
        outline.setContent(outlineDetails.getContent());
        outline.setChapterNumber(outlineDetails.getChapterNumber());
        outline.setPlotOrder(outlineDetails.getPlotOrder());
        outline.setStatus(outlineDetails.getStatus());

        return outlineRepository.save(outline);
    }

    public void deleteOutline(Long id) {
        outlineRepository.deleteById(id);
    }

    public List<Outline> searchOutlines(Long novelId, String keyword) {
        return outlineRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public List<Outline> getOutlinesByStatus(Long novelId, String status) {
        return outlineRepository.findByNovelIdAndStatus(novelId, status);
    }

    public Outline addTagToOutline(Long outlineId, Long tagId) {
        Outline outline = outlineRepository.findById(outlineId)
                .orElseThrow(() -> new RuntimeException("Outline not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        outline.getTags().add(tag);
        return outlineRepository.save(outline);
    }

    public Outline removeTagFromOutline(Long outlineId, Long tagId) {
        Outline outline = outlineRepository.findById(outlineId)
                .orElseThrow(() -> new RuntimeException("Outline not found"));

        outline.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return outlineRepository.save(outline);
    }

    public Outline setOutlineTags(Long outlineId, Set<Long> tagIds) {
        Outline outline = outlineRepository.findById(outlineId)
                .orElseThrow(() -> new RuntimeException("Outline not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found: " + tagId));
            tags.add(tag);
        }
        outline.setTags(tags);
        return outlineRepository.save(outline);
    }
}
