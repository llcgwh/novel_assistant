package com.novelwriting.service;

import com.novelwriting.entity.Tag;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class TagService {

    @Autowired
    private TagRepository tagRepository;

    public List<Tag> getTagsByNovelId(Long novelId) {
        return tagRepository.findByNovelId(novelId);
    }

    public Optional<Tag> getTagById(Long id) {
        return tagRepository.findById(id);
    }

    public Tag createTag(Tag tag) {
        return tagRepository.save(tag);
    }

    public Tag updateTag(Long id, Tag tagDetails) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        tag.setName(tagDetails.getName());
        tag.setColor(tagDetails.getColor());
        tag.setDescription(tagDetails.getDescription());

        return tagRepository.save(tag);
    }

    public void deleteTag(Long id) {
        tagRepository.deleteById(id);
    }

    public List<Tag> searchTags(Long novelId, String name) {
        return tagRepository.findByNovelIdAndNameContainingIgnoreCase(novelId, name);
    }
}
