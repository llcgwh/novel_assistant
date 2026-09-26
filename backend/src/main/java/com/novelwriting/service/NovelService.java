package com.novelwriting.service;

import com.novelwriting.entity.Novel;
import com.novelwriting.repository.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class NovelService {

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    @Autowired
    private NovelRepository novelRepository;

    public List<Novel> getAllNovels() {
        return novelRepository.findAll();
    }

    public Optional<Novel> getNovelById(Long id) {
        return novelRepository.findById(id);
    }

    public Novel createNovel(Novel novel) {
        return novelRepository.save(novel);
    }

    public Novel updateNovel(Long id, Novel novelDetails) {
        Novel novel = novelRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        novel.setTitle(novelDetails.getTitle());
        novel.setDescription(novelDetails.getDescription());
        novel.setAuthor(novelDetails.getAuthor());
        novel.setGenre(novelDetails.getGenre());
        novel.setStatus(novelDetails.getStatus());
        novel.setCoverImage(novelDetails.getCoverImage());

        return novelRepository.save(novel);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteNovel(Long id) {
        for(String entity : List.of("WritingRevision","WritingSession","WritingChapter","WritingBook"))
            em.createQuery("delete from "+entity+" where novelId=:id").setParameter("id",id).executeUpdate();
        novelRepository.deleteById(id);
    }

    public List<Novel> searchNovels(String title) {
        return novelRepository.findByTitleContainingIgnoreCase(title);
    }

    public List<Novel> getNovelsByStatus(String status) {
        return novelRepository.findByStatus(status);
    }
}
