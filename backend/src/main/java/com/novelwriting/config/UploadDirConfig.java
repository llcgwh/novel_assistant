package com.novelwriting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class UploadDirConfig {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureUploadDirectoryExists() {
        try {
            Path path = Paths.get(uploadDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("Created upload directory: " + path.toAbsolutePath());
            }
            
            // Also ensure a sample novel subdirectory exists to test write permissions
            Path sampleNovelDir = path.resolve("sample");
            if (!Files.exists(sampleNovelDir)) {
                Files.createDirectories(sampleNovelDir);
                System.out.println("Created sample novel directory: " + sampleNovelDir.toAbsolutePath());
                
                // Clean up the sample directory
                try {
                    Files.delete(sampleNovelDir);
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
            
            System.out.println("Upload directory is ready: " + path.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to create upload directory: " + e.getMessage());
        }
    }
}