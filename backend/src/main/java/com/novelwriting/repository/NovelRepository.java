package com.novelwriting.repository;

import com.novelwriting.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NovelRepository extends JpaRepository<Novel, Long> {
    List<Novel> findByTitleContainingIgnoreCase(String title);
    List<Novel> findByStatus(String status);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Novel n set n.lastWebdavSync=:time where n.id=:id")
    int updateLastWebdavSync(@org.springframework.data.repository.query.Param("id") Long id,
                            @org.springframework.data.repository.query.Param("time") java.time.LocalDateTime time);
}
