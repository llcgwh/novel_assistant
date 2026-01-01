package com.novelwriting.repository;

import com.novelwriting.entity.CharacterRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRelationshipRepository extends JpaRepository<CharacterRelationship, Long> {
    List<CharacterRelationship> findByNovelId(Long novelId);

    @Query("SELECT cr FROM CharacterRelationship cr WHERE cr.novelId = :novelId AND (cr.characterId1 = :characterId OR cr.characterId2 = :characterId)")
    List<CharacterRelationship> findByNovelIdAndCharacterId(@Param("novelId") Long novelId, @Param("characterId") Long characterId);
}
