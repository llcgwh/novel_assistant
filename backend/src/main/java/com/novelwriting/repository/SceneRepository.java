package com.novelwriting.repository;

import com.novelwriting.entity.Scene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SceneRepository extends JpaRepository<Scene, Long> {
    List<Scene> findByNovelId(Long novelId);

    @Query("SELECT s FROM Scene s WHERE s.novelId = :novelId AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(s.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Scene> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT s FROM Scene s JOIN s.tags t WHERE s.novelId = :novelId AND t.id = :tagId")
    List<Scene> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);

    @Query("SELECT DISTINCT s FROM Scene s JOIN s.tags t WHERE s.novelId = :novelId AND t.id IN :tagIds")
    List<Scene> findByNovelIdAndTagIds(@Param("novelId") Long novelId, @Param("tagIds") List<Long> tagIds);

    @Query("SELECT DISTINCT s FROM Scene s LEFT JOIN FETCH s.tags WHERE s.novelId = :novelId")
    List<Scene> findByNovelIdWithTags(@Param("novelId") Long novelId);
}
