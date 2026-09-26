package com.novelwriting.maintenance;

import com.novelwriting.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline maintenance: unreferenced files are moved into a reversible quarantine, never deleted. */
@Service
public class ImageMaintenance {
    private final ImageRepository images;
    private final Path root;
    public ImageMaintenance(ImageRepository images, @Value("${app.upload.dir:uploads}") String directory) {
        this.images = images; this.root = Path.of(directory).toAbsolutePath().normalize();
    }
    public List<Path> scan() throws Exception {
        if (!Files.exists(root)) return List.of();
        if (Files.isSymbolicLink(root)) throw new IllegalStateException("Upload root must not be a symbolic link");
        Set<Path> referenced = new HashSet<>();
        for (var image : images.findAll()) referenced.add(Path.of(image.getFilePath()).toAbsolutePath().normalize());
        List<Path> orphaned = new ArrayList<>();
        try (var folders = Files.list(root)) {
            for (Path folder : folders.toList()) {
                if (!folder.getFileName().toString().matches("[1-9][0-9]*") || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var files = Files.list(folder)) {
                    for (Path file : files.toList())
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !referenced.contains(file)
                                && Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(Duration.ofHours(1)))) orphaned.add(file);
                }
            }
        }
        return orphaned;
    }
    public String quarantine() throws Exception {
        List<Path> files = scan();
        if (files.isEmpty()) return "No orphaned images older than one hour";
        String batch = UUID.randomUUID().toString();
        Path quarantine = root.resolve(".quarantine");
        if (Files.isSymbolicLink(quarantine)) throw new IllegalStateException("Unsafe quarantine path");
        Path destination = quarantine.resolve(batch);
        Files.createDirectories(destination);
        for (Path file : files) {
            Path target = destination.resolve(root.relativize(file));
            Files.createDirectories(target.getParent());
            Files.move(file, target); // No overwrite; a partially moved batch can also be restored.
        }
        return "Quarantined " + files.size() + " files. Restore batch: " + batch;
    }
    public int restore(String batch) throws Exception {
        batch = UUID.fromString(batch).toString();
        Path source = root.resolve(".quarantine").resolve(batch);
        if (!source.toRealPath().startsWith(root.toRealPath().resolve(".quarantine"))) throw new IllegalStateException("Unsafe batch");
        int count = 0;
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path relative = source.relativize(file);
                if (relative.getNameCount() != 2 || !relative.getName(0).toString().matches("[1-9][0-9]*")) throw new IllegalStateException("Invalid quarantine entry");
                Path destination = root.resolve(relative);
                Files.createDirectories(destination.getParent());
                if (!destination.getParent().toRealPath().startsWith(root.toRealPath())) throw new IllegalStateException("Unsafe restore directory");
                Files.move(file, destination); count++;
            }
        }
        return count;
    }
}
