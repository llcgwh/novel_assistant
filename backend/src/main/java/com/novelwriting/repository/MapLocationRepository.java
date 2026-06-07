package com.novelwriting.repository;

import com.novelwriting.entity.MapLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MapLocationRepository extends JpaRepository<MapLocation, Long> {
    List<MapLocation> findByNovelId(Long novelId);

    @Query("SELECT m FROM MapLocation m WHERE m.novelId = :novelId AND (LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(m.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<MapLocation> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT m FROM MapLocation m JOIN m.tags t WHERE m.novelId = :novelId AND t.id = :tagId")
    List<MapLocation> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);

    @Query("SELECT DISTINCT m FROM MapLocation m JOIN m.tags t WHERE m.novelId = :novelId AND t.id IN :tagIds")
    List<MapLocation> findByNovelIdAndTagIds(@Param("novelId") Long novelId, @Param("tagIds") List<Long> tagIds);

    List<MapLocation> findByNovelIdAndLocationType(Long novelId, String locationType);

    @Query("SELECT DISTINCT m FROM MapLocation m LEFT JOIN FETCH m.tags LEFT JOIN FETCH m.parentLocation WHERE m.novelId = :novelId")
    List<MapLocation> findByNovelIdWithTags(@Param("novelId") Long novelId);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM MapLocation m WHERE m.novelId = :novelId")
    void deleteByNovelId(@org.springframework.data.repository.query.Param("novelId") Long novelId);
}
