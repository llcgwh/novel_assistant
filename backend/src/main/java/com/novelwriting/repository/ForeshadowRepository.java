package com.novelwriting.repository;

import com.novelwriting.entity.Foreshadow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ForeshadowRepository extends JpaRepository<Foreshadow, Long> {
}
