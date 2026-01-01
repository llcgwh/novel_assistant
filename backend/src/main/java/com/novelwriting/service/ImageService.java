package com.novelwriting.service;

import com.novelwriting.entity.Image;
import com.novelwriting.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ImageService {

    @Autowired
    private ImageRepository imageRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    public List<Image> getImagesByNovelId(Long novelId) {
        return imageRepository.findByNovelId(novelId);
    }

    public List<Image> getImagesByNovelIdAndType(Long novelId, String imageType) {
        return imageRepository.findByNovelIdAndImageType(novelId, imageType);
    }

    public Optional<Image> getImageById(Long id) {
        return imageRepository.findById(id);
    }

    public Image uploadImage(Long novelId, MultipartFile file, String imageType) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".jpg";
        String filename = UUID.randomUUID().toString() + extension;

        Path uploadPath = Paths.get(uploadDir, novelId.toString());
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath);

        Image image = new Image();
        image.setNovelId(novelId);
        image.setFilename(filename);
        image.setOriginalName(originalFilename);
        image.setFilePath(filePath.toString());
        image.setFileSize((int) file.getSize());
        image.setMimeType(file.getContentType());
        image.setImageType(imageType);

        return imageRepository.save(image);
    }

    public void deleteImage(Long id) throws IOException {
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found"));

        Path filePath = Paths.get(image.getFilePath());
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }

        imageRepository.deleteById(id);
    }
}
