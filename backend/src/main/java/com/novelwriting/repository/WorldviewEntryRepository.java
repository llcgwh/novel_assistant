package com.novelwriting.repository;

import com.novelwriting.entity.WorldviewEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorldviewEntryRepository extends JpaRepository<WorldviewEntry, Long> {

    List<WorldviewEntry> findByNovelId(Long novelId);

    List<WorldviewEntry> findByNovelIdAndCategory(Long novelId, String category);

    @Query("SELECT w FROM WorldviewEntry w WHERE w.novelId = :novelId AND (LOWER(w.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(w.content) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<WorldviewEntry> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT DISTINCT w FROM WorldviewEntry w WHERE w.novelId = :novelId AND w.category = :category AND (LOWER(w.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(w.content) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<WorldviewEntry> searchByNovelIdAndCategoryAndKeyword(@Param("novelId") Long novelId, @Param("category") String category, @Param("keyword") String keyword);

    @Query("SELECT DISTINCT w FROM WorldviewEntry w LEFT JOIN FETCH w.tags WHERE w.novelId = :novelId")
    List<WorldviewEntry> findByNovelIdWithTags(@Param("novelId") Long novelId);

    @Query("SELECT DISTINCT w FROM WorldviewEntry w LEFT JOIN FETCH w.tags WHERE w.id = :id")
    java.util.Optional<WorldviewEntry> findByIdWithTags(@Param("id") Long id);

    @Query("SELECT DISTINCT w FROM WorldviewEntry w JOIN w.tags t WHERE w.novelId = :novelId AND t.id = :tagId")
    List<WorldviewEntry> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM WorldviewEntry w WHERE w.novelId = :novelId")
    void deleteByNovelId(@org.springframework.data.repository.query.Param("novelId") Long novelId);
}
