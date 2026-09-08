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
        // 验证上传的文件
        if (file.isEmpty()) {
            throw new IOException("Cannot upload empty file");
        }
        
        // 验证文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IOException("Only image files are allowed");
        }
        
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IOException("File name is invalid");
        }
        
        // 验证文件扩展名
        String extension = "";
        int lastDotIndex = originalFilename.lastIndexOf(".");
        if (lastDotIndex > 0) {
            extension = originalFilename.substring(lastDotIndex);
            // 验证扩展名是否为图片格式
            if (!isValidImageExtension(extension)) {
                throw new IOException("Invalid image file extension: " + extension);
            }
        } else {
            extension = ".jpg"; // 默认扩展名
        }

        String filename = UUID.randomUUID().toString() + extension;

        // 安全路径构建，防止路径遍历
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().resolve(novelId.toString()).normalize();
        
        // 确保上传路径在指定目录下
        Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!uploadPath.startsWith(uploadDirPath)) {
            throw new IOException("Invalid upload path");
        }
        
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        Path filePath = uploadPath.resolve(filename);
        // 确保最终路径也在上传目录内
        if (!filePath.normalize().startsWith(uploadDirPath)) {
            throw new IOException("Invalid file path");
        }
        
        Files.copy(file.getInputStream(), filePath);

        Image image = new Image();
        image.setNovelId(novelId);
        image.setFilename(filename);
        image.setOriginalName(originalFilename);
        image.setFilePath(filePath.toString());
        image.setFileSize((int) file.getSize());
        image.setMimeType(contentType);
        image.setImageType(imageType);

        return imageRepository.save(image);
    }
    
    private boolean isValidImageExtension(String extension) {
        String lowerExt = extension.toLowerCase();
        return ".jpg".equals(lowerExt) || ".jpeg".equals(lowerExt) || 
               ".png".equals(lowerExt) || ".gif".equals(lowerExt) || 
               ".bmp".equals(lowerExt) || ".webp".equals(lowerExt);
    }

    public void deleteImage(Long id) throws IOException {
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found"));

        // 验证文件路径安全性，防止路径遍历
        Path filePath = Paths.get(image.getFilePath()).toAbsolutePath().normalize();
        Path uploadDirPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        
        if (!filePath.startsWith(uploadDirPath)) {
            throw new IOException("Invalid file path for deletion");
        }
        
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }

        imageRepository.deleteById(id);
    }
}
