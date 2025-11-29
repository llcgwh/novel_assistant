package com.novelwriting.repository;

import com.novelwriting.entity.Outline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutlineRepository extends JpaRepository<Outline, Long> {
}
