package com.novelwriting.service;

import com.novelwriting.entity.Foreshadow;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.ForeshadowRepository;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@org.springframework.transaction.annotation.Transactional
public class ForeshadowService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<Foreshadow> getForeshadowsByNovelId(Long novelId) {
        return foreshadowRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<Foreshadow> getForeshadowById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, id);
        return foreshadowRepository.findById(id);
    }

    public Foreshadow createForeshadow(Foreshadow foreshadow) {
        Long novelId = foreshadow.getNovelId();
        scope.requireNovel(novelId);
        foreshadow.setId(null);
        scope.validateLinks(foreshadow, novelId, null);
        return foreshadowRepository.save(foreshadow);
    }

    public Foreshadow updateForeshadow(Long novelId, Long id, Foreshadow foreshadowDetails) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, id);
        scope.validateLinks(foreshadowDetails, novelId, id);
        Foreshadow foreshadow = foreshadowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Foreshadow not found"));

        foreshadow.setTitle(foreshadowDetails.getTitle());
        foreshadow.setContent(foreshadowDetails.getContent());
        foreshadow.setLaidAt(foreshadowDetails.getLaidAt());
        foreshadow.setRevealedAt(foreshadowDetails.getRevealedAt());
        foreshadow.setStatus(foreshadowDetails.getStatus());

        return foreshadowRepository.save(foreshadow);
    }

    public void deleteForeshadow(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, id);
        foreshadowRepository.deleteById(id);
    }

    public List<Foreshadow> searchForeshadows(Long novelId, String keyword) {
        return foreshadowRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public List<Foreshadow> getForeshadowsByStatus(Long novelId, String status) {
        return foreshadowRepository.findByNovelIdAndStatus(novelId, status);
    }

    public Foreshadow addTagToForeshadow(Long novelId, Long foreshadowId, Long tagId) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        Foreshadow foreshadow = foreshadowRepository.findById(foreshadowId)
                .orElseThrow(() -> new RuntimeException("Foreshadow not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        foreshadow.getTags().add(tag);
        return foreshadowRepository.save(foreshadow);
    }

    public Foreshadow removeTagFromForeshadow(Long novelId, Long foreshadowId, Long tagId) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        Foreshadow foreshadow = foreshadowRepository.findById(foreshadowId)
                .orElseThrow(() -> new RuntimeException("Foreshadow not found"));

        foreshadow.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return foreshadowRepository.save(foreshadow);
    }

    public Foreshadow setForeshadowTags(Long novelId, Long foreshadowId, Set<Long> tagIds) {
        scope.require(com.novelwriting.entity.Foreshadow.class, novelId, foreshadowId);
        scope.requireAll(com.novelwriting.entity.Tag.class, novelId, tagIds);
        Foreshadow foreshadow = foreshadowRepository.findById(foreshadowId)
                .orElseThrow(() -> new RuntimeException("Foreshadow not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found: " + tagId));
            tags.add(tag);
        }
        foreshadow.setTags(tags);
        return foreshadowRepository.save(foreshadow);
    }
}
