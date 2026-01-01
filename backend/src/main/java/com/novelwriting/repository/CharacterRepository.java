package com.novelwriting.repository;

import com.novelwriting.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {
    List<Character> findByNovelId(Long novelId);

    @Query("SELECT c FROM Character c WHERE c.novelId = :novelId AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(c.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Character> searchByNovelIdAndKeyword(@Param("novelId") Long novelId, @Param("keyword") String keyword);

    @Query("SELECT c FROM Character c JOIN c.tags t WHERE c.novelId = :novelId AND t.id = :tagId")
    List<Character> findByNovelIdAndTagId(@Param("novelId") Long novelId, @Param("tagId") Long tagId);
}
