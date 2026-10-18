package com.novelwriting.service;

import com.novelwriting.entity.Image;
import com.novelwriting.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@org.springframework.transaction.annotation.Transactional(rollbackFor = IOException.class)
public class ImageService {

    @Autowired
    private NovelScope scope;

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

    public Optional<Image> getImageById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.Image.class, novelId, id);
        return imageRepository.findById(id);
    }

    public Image uploadImage(Long novelId, MultipartFile file, String imageType) throws IOException {
        scope.requireNovel(novelId);
        if (file == null || file.isEmpty())
            throw new ImageUploadProblem(400, "IMAGE_EMPTY", "图片文件为空，请重新选择图片。");
        if (file.getSize() > ImageUploadPolicy.MAX_IMAGE_BYTES) throw ImageUploadProblem.tooLarge();
        if (imageType == null || imageType.isBlank() || imageType.length() > 50)
            throw new ImageUploadProblem(400, "IMAGE_TYPE_INVALID", "图片用途无效，请重新打开编辑窗口后重试。");
        ImageUploadPolicy.Format format = ImageUploadPolicy.format(file.getOriginalFilename(), file.getContentType());
        byte[] bytes;
        try (InputStream stream = file.getInputStream()) {
            bytes = stream.readNBytes(ImageUploadPolicy.MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length > ImageUploadPolicy.MAX_IMAGE_BYTES) throw ImageUploadProblem.tooLarge();
        if (bytes.length == 0) throw new ImageUploadProblem(400, "IMAGE_EMPTY", "图片文件为空，请重新选择图片。");
        ImageUploadPolicy.verifySignature(bytes, format.mimeType());

        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();
        Path folder = root.resolve(novelId.toString());
        Files.createDirectories(folder);
        if (!folder.toRealPath().equals(folder)) throw new IOException("Unsafe image upload directory");
        String filename = UUID.randomUUID() + format.extension();
        Path filePath = folder.resolve(filename);
        boolean created = false;
        try {
            Files.createFile(filePath);
            created = true;
            Files.write(filePath, bytes);
            // A later transaction failure must not leave an orphan file behind.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) try { Files.deleteIfExists(filePath); } catch (IOException ignored) { /* Maintenance can reclaim an inaccessible orphan. */ }
                    }
                });
            }
            Image image = new Image();
            image.setNovelId(novelId);
            image.setFilename(filename);
            image.setOriginalName(file.getOriginalFilename());
            image.setFilePath(filePath.toString());
            image.setFileSize(bytes.length);
            image.setMimeType(format.mimeType());
            image.setImageType(imageType);
            return imageRepository.saveAndFlush(image);
        } catch (IOException | RuntimeException failure) {
            if (created) try { Files.deleteIfExists(filePath); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException io) throw io;
            throw new ImageUploadProblem(500, "IMAGE_STORAGE_FAILED", "图片保存失败，请检查本机存储空间和上传目录权限后重试。");
        }
    }

    public void deleteImage(Long novelId, Long id) throws IOException {
        scope.require(com.novelwriting.entity.Image.class, novelId, id);
        Image image = imageRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Image not found"));

        // 验证文件路径安全性，防止路径遍历
        Path filePath = ImageStoragePaths.resolveInside(uploadDir, image.getFilePath());
        
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }

        imageRepository.deleteById(id);
    }
}
