package com.novelwriting.repository;

import com.novelwriting.entity.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {
    List<TimelineEvent> findAllByOrderByRealOrderAsc();
    List<TimelineEvent> findByNovelIdOrderByRealOrderAsc(Long novelId);

    @Query("SELECT t FROM TimelineEvent t WHERE t.novelId = :novelId AND (LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(t.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<TimelineEvent> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT t FROM TimelineEvent t JOIN t.tags tag WHERE t.novelId = :novelId AND tag.id = :tagId")
    List<TimelineEvent> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);

    @Query("SELECT DISTINCT t FROM TimelineEvent t JOIN t.tags tag WHERE t.novelId = :novelId AND tag.id IN :tagIds")
    List<TimelineEvent> findByNovelIdAndTagIds(@Param("novelId") Long novelId, @Param("tagIds") List<Long> tagIds);

    @Query("SELECT DISTINCT t FROM TimelineEvent t " +
           "LEFT JOIN FETCH t.characters " +
           "LEFT JOIN FETCH t.scenes " +
           "LEFT JOIN FETCH t.foreshadows " +
           "LEFT JOIN FETCH t.outlines " +
           "LEFT JOIN FETCH t.tags " +
           "WHERE t.novelId = :novelId " +
           "ORDER BY t.realOrder ASC")
    List<TimelineEvent> findByNovelIdWithRelations(@Param("novelId") Long novelId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM TimelineEvent t WHERE t.novelId = :novelId")
    void deleteByNovelId(@org.springframework.data.repository.query.Param("novelId") Long novelId);
}
