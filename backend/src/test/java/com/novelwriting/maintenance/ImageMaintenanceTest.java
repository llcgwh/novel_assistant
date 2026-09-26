package com.novelwriting.maintenance;

import com.novelwriting.entity.Image;
import com.novelwriting.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageMaintenanceTest {
    @TempDir Path root;
    @Test void keepsReferencedAndRecentFilesAndRestoresQuarantine() throws Exception {
        Path folder = Files.createDirectory(root.resolve("1"));
        Path kept = Files.writeString(folder.resolve("kept.png"), "kept");
        Path old = Files.writeString(folder.resolve("old.png"), "old");
        Path recent = Files.writeString(folder.resolve("recent.png"), "recent");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(7200)));
        Image image = new Image(); image.setFilePath(kept.toString());
        ImageRepository images = mock(ImageRepository.class); when(images.findAll()).thenReturn(List.of(image));
        ImageMaintenance service = new ImageMaintenance(images, root.toString());
        assertEquals(List.of(old), service.scan());
        String result = service.quarantine();
        String batch = result.substring(result.lastIndexOf(' ') + 1);
        assertFalse(Files.exists(old)); assertTrue(Files.exists(kept)); assertTrue(Files.exists(recent));
        assertEquals(1, service.restore(batch)); assertEquals("old", Files.readString(old));
    }
    @Test void ignoresSymlinksAndRejectsInvalidBatchNames() throws Exception {
        Files.createDirectories(root.resolve("1"));
        Files.createSymbolicLink(root.resolve("1/link.png"), root.resolve("missing"));
        ImageRepository images = mock(ImageRepository.class); when(images.findAll()).thenReturn(List.of());
        ImageMaintenance service = new ImageMaintenance(images, root.toString());
        assertTrue(service.scan().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.restore("../outside"));
    }
}
