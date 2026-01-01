package com.novelwriting.repository;

import com.novelwriting.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NovelRepository extends JpaRepository<Novel, Long> {
    List<Novel> findByTitleContainingIgnoreCase(String title);
    List<Novel> findByStatus(String status);
}
