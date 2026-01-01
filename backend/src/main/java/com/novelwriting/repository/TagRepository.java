package com.novelwriting.repository;

import com.novelwriting.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    List<Tag> findByNovelId(Long novelId);
    Optional<Tag> findByNovelIdAndName(Long novelId, String name);
    List<Tag> findByNovelIdAndNameContainingIgnoreCase(Long novelId, String name);
}
