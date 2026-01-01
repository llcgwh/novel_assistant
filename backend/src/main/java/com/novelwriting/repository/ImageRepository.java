package com.novelwriting.repository;

import com.novelwriting.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {
    List<Image> findByNovelId(Long novelId);
    List<Image> findByNovelIdAndImageType(Long novelId, String imageType);
}
