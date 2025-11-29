package com.novelwriting.repository;

import com.novelwriting.entity.MapLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MapLocationRepository extends JpaRepository<MapLocation, Long> {
}
