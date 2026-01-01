package com.novelwriting.controller;

import com.novelwriting.entity.Image;
import com.novelwriting.service.ImageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/novels/{novelId}/images")
@CrossOrigin(origins = "*")
public class ImageController {

    @Autowired
    private ImageService imageService;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @GetMapping
    public List<Image> getImagesByNovelId(@PathVariable Long novelId) {
        return imageService.getImagesByNovelId(novelId);
    }

    @GetMapping("/type/{imageType}")
    public List<Image> getImagesByType(@PathVariable Long novelId, @PathVariable String imageType) {
        return imageService.getImagesByNovelIdAndType(novelId, imageType);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Image> getImageById(@PathVariable Long novelId, @PathVariable Long id) {
        return imageService.getImageById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Image> uploadImage(
            @PathVariable Long novelId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "imageType", defaultValue = "other") String imageType) {
        try {
            Image image = imageService.uploadImage(novelId, file, imageType);
            return ResponseEntity.ok(image);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long novelId, @PathVariable Long id) {
        try {
            imageService.deleteImage(id);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> getImageFile(@PathVariable Long novelId, @PathVariable Long id) {
        try {
            Image image = imageService.getImageById(id)
                    .orElseThrow(() -> new RuntimeException("Image not found"));

            Path filePath = Paths.get(image.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getMimeType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.getOriginalName() + "\"")
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
