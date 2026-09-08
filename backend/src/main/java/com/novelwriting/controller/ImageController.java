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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

            // 验证文件路径安全性，防止路径遍历
            Path filePath = Paths.get(image.getFilePath()).toAbsolutePath().normalize();
            Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            
            if (!filePath.startsWith(uploadDirPath)) {
                return ResponseEntity.badRequest().build();
            }
            
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                // 使用 RFC 5987 编码处理中文文件名
                String encodedFilename = URLEncoder.encode(image.getOriginalName(), StandardCharsets.UTF_8)
                        .replace("+", "%20");
                String contentDisposition = "inline; filename*=UTF-8''" + encodedFilename;

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getMimeType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
