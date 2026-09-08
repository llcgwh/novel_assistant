package com.novelwriting.service;

import com.novelwriting.entity.Tag;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@org.springframework.transaction.annotation.Transactional
public class TagService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private TagRepository tagRepository;

    public List<Tag> getTagsByNovelId(Long novelId) {
        return tagRepository.findByNovelId(novelId);
    }

    public Optional<Tag> getTagById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Tag.class, novelId, id);
        return tagRepository.findById(id);
    }

    public Tag createTag(Tag tag) {
        Long novelId = tag.getNovelId();
        scope.requireNovel(novelId);
        tag.setId(null);
        scope.validateLinks(tag, novelId, null);
        return tagRepository.save(tag);
    }

    public Tag updateTag(Long novelId, Long id, Tag tagDetails) {
        scope.require(com.novelwriting.entity.Tag.class, novelId, id);
        scope.validateLinks(tagDetails, novelId, id);
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        tag.setName(tagDetails.getName());
        tag.setColor(tagDetails.getColor());
        tag.setDescription(tagDetails.getDescription());

        return tagRepository.save(tag);
    }

    public void deleteTag(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Tag.class, novelId, id);
        tagRepository.deleteById(id);
    }

    public List<Tag> searchTags(Long novelId, String name) {
        return tagRepository.findByNovelIdAndNameContainingIgnoreCase(novelId, name);
    }
}
