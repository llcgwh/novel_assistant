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
        // Serialize with manuscript/restore writes before removing any owned data.
        if (em.find(Novel.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) == null) return;
        // Databases upgraded by Hibernate and the original SQL scripts have different
        // FK cascade rules. Delete owners in dependency order: Hibernate also removes
        // each owner's mapped join-table rows before its bulk entity deletion.
        em.createQuery("update RelationshipGroup set parentGroupId=null where novelId=:id")
                .setParameter("id", id).executeUpdate();
        em.createQuery("update MapLocation set parentLocation=null where novelId=:id")
                .setParameter("id", id).executeUpdate();
        for (String entity : List.of(
                "RelationshipGroup", "CharacterRelationship", "WorldviewEntry", "TimelineEvent",
                "Foreshadow", "Outline", "Scene", "MapLocation", "Character", "Tag", "Image",
                "WritingRevision", "WritingSession", "WritingChapter", "WritingBook",
                "FormSubmission", "BackupCleanup", "BackupRetention")) {
            em.createQuery("delete from " + entity + " where novelId=:id")
                    .setParameter("id", id).executeUpdate();
        }
        em.createQuery("delete from SeriesMutationReceipt where scope like :scope")
                .setParameter("scope", "novel:" + id + ":%").executeUpdate();
        // Keep device-local audit history, shared templates and recoverable files.
        novelRepository.deleteById(id);
        em.flush();
    }

    public List<Novel> searchNovels(String title) {
        return novelRepository.findByTitleContainingIgnoreCase(title);
    }

    public List<Novel> getNovelsByStatus(String status) {
        return novelRepository.findByStatus(status);
    }
}
