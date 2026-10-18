package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.novelwriting.entity.Image;
import com.novelwriting.repository.ImageRepository;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;

class ImageUploadServiceTest {
    @TempDir Path root;
    ImageRepository repository;
    ImageService service;
    static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};
    @BeforeEach void setup() {
        repository = mock(ImageRepository.class); service = new ImageService();
        ReflectionTestUtils.setField(service, "imageRepository", repository);
        ReflectionTestUtils.setField(service, "scope", mock(NovelScope.class));
        ReflectionTestUtils.setField(service, "uploadDir", root.toString());
        when(repository.saveAndFlush(any(Image.class))).thenAnswer(call -> call.getArgument(0));
    }
    @Test void serviceChecksActualStreamSizeAndClosesItEvenIfReportedSizeLies() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        byte[] bytes = Arrays.copyOf(PNG, ImageUploadPolicy.MAX_IMAGE_BYTES + 1);
        var file = new MockMultipartFile("file", "portrait.png", "image/png", bytes) {
            @Override public long getSize() { return 100; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes) { @Override public void close() { closed.set(true); } }; }
        };
        assertEquals(413, assertThrows(ImageUploadProblem.class, () -> service.uploadImage(1L, file, "character_portrait")).status());
        assertTrue(closed.get()); verifyNoInteractions(repository);
        try (var paths = Files.walk(root)) { assertEquals(0, paths.filter(Files::isRegularFile).count()); }
    }
    @Test void serviceLimitCannotBeBypassedWhenMultipartParsingIsNotUsed() {
        var file = new MockMultipartFile("file", "portrait.png", "image/png", Arrays.copyOf(PNG, ImageUploadPolicy.MAX_IMAGE_BYTES + 1));
        assertEquals("IMAGE_TOO_LARGE", assertThrows(ImageUploadProblem.class, () -> service.uploadImage(1L, file, "character_portrait")).code());
        verifyNoInteractions(repository);
    }
    @Test void transactionRollbackDeletesTheUploadedFile() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            Image uploaded = service.uploadImage(1L, new MockMultipartFile("file", "portrait.png", "image/png", PNG), "character_portrait");
            Path file = Path.of(uploaded.getFilePath()); assertTrue(Files.exists(file));
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertFalse(Files.exists(file));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void successfulTransactionKeepsTheFileAndPathCannotEscapeThroughNovelDirectory() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            var uploaded = service.uploadImage(1L, new MockMultipartFile("file", "portrait.png", "image/png", PNG), "character_portrait");
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertTrue(Files.exists(Path.of(uploaded.getFilePath())));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        Path outside = Files.createDirectory(root.resolve("outside")); Files.createSymbolicLink(root.resolve("2"), outside);
        assertThrows(IOException.class, () -> service.uploadImage(2L, new MockMultipartFile("file", "portrait.png", "image/png", PNG), "character_portrait"));
        try (var paths = Files.list(outside)) { assertEquals(0, paths.count()); }
    }
    @Test void canonicalReadPathsKeepMissingFileSupportButRejectSymlinkEscapes() throws Exception {
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), allowed);
        assertEquals(allowed.toRealPath().resolve("1/missing.png"), ImageStoragePaths.resolveInside(alias.toString(), allowed.resolve("1/missing.png").toString()));
        assertEquals(allowed.toRealPath().resolve("1/missing.png"), ImageStoragePaths.resolveInside(alias.toString(), alias.resolve("1/missing.png").toString()));
        Files.createSymbolicLink(allowed.resolve("escape"), outside);
        assertThrows(IOException.class, () -> ImageStoragePaths.resolveInside(alias.toString(), allowed.resolve("escape/missing.png").toString()));
    }
}
