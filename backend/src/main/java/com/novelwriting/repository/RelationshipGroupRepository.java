package com.novelwriting.repository;

import com.novelwriting.entity.RelationshipGroup;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationshipGroupRepository extends JpaRepository<RelationshipGroup, Long> {

    @EntityGraph(attributePaths = {"characters"})
    List<RelationshipGroup> findByNovelId(Long novelId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM RelationshipGroup g WHERE g.novelId = :novelId")
    void deleteByNovelId(@org.springframework.data.repository.query.Param("novelId") Long novelId);

    @Override
    @EntityGraph(attributePaths = {"characters"})
    Optional<RelationshipGroup> findById(Long id);
}
