package com.novelwriting.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Deque;

/** Canonicalize both sides, including missing files and aliases such as macOS /tmp. */
public final class ImageStoragePaths {
    public static Path resolveInside(String uploadDir, String storedPath) throws IOException {
        Path root = canonical(Paths.get(uploadDir));
        Path file = canonical(Paths.get(storedPath));
        if (file.equals(root) || !file.startsWith(root)) throw new IOException("Image path outside upload directory");
        return file;
    }
    private static Path canonical(Path path) throws IOException {
        Path cursor = path.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        while (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(cursor.getFileName()); cursor = cursor.getParent();
            if (cursor == null) throw new IOException("Cannot resolve image storage path");
        }
        Path result = cursor.toRealPath();
        for (Path segment : missing) result = result.resolve(segment);
        return result;
    }
    private ImageStoragePaths() {}
}
