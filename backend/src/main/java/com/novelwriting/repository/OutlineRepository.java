package com.novelwriting.repository;

import com.novelwriting.entity.Outline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutlineRepository extends JpaRepository<Outline, Long> {
    List<Outline> findByNovelIdOrderByPlotOrderAsc(Long novelId);

    @Query("SELECT o FROM Outline o WHERE o.novelId = :novelId AND (LOWER(o.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(o.content) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Outline> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT o FROM Outline o JOIN o.tags t WHERE o.novelId = :novelId AND t.id = :tagId")
    List<Outline> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);

    @Query("SELECT DISTINCT o FROM Outline o JOIN o.tags t WHERE o.novelId = :novelId AND t.id IN :tagIds")
    List<Outline> findByNovelIdAndTagIds(@Param("novelId") Long novelId, @Param("tagIds") List<Long> tagIds);

    List<Outline> findByNovelIdAndStatus(Long novelId, String status);

    @Query("SELECT DISTINCT o FROM Outline o LEFT JOIN FETCH o.tags WHERE o.novelId = :novelId")
    List<Outline> findByNovelIdWithTags(@Param("novelId") Long novelId);
}
