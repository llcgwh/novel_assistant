package com.novelwriting.repository;

import com.novelwriting.entity.Foreshadow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ForeshadowRepository extends JpaRepository<Foreshadow, Long> {
    List<Foreshadow> findByNovelId(Long novelId);

    @Query("SELECT f FROM Foreshadow f WHERE f.novelId = :novelId AND (LOWER(f.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(f.content) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Foreshadow> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT f FROM Foreshadow f JOIN f.tags t WHERE f.novelId = :novelId AND t.id = :tagId")
    List<Foreshadow> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);

    List<Foreshadow> findByNovelIdAndStatus(Long novelId, String status);
}
