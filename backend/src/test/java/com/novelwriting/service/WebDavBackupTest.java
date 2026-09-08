package com.novelwriting.service;

import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.novelwriting.entity.Novel;
import com.novelwriting.repository.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebDavBackupTest {
    @Mock NovelRepository novels;
    @Mock BackupBundleService bundles;
    @Mock Sardine sardine;
    @InjectMocks WebDavSyncService service;

    Novel novel(String title) {
        Novel novel = new Novel(); novel.setId(1L); novel.setTitle(title);
        novel.setWebdavServerUrl("https://dav.example/"); novel.setWebdavUsername("user"); novel.setWebdavPassword("secret");
        return novel;
    }

    @Test void uploadSendsImageBundle() throws Exception {
        Novel novel = novel("Story"); byte[] bytes = {1, 2, 3};
        when(novels.findById(1L)).thenReturn(Optional.of(novel));
        when(bundles.exportBundle(1L)).thenReturn(bytes);
        try (MockedStatic<SardineFactory> factory = mockStatic(SardineFactory.class)) {
            factory.when(() -> SardineFactory.begin("user", "secret")).thenReturn(sardine);
            var result = service.syncUpload(1L);
            assertEquals(true, result.get("success"));
            assertTrue(result.get("filename").toString().endsWith(".backup.json"));
            verify(sardine).put(eq("https://dav.example/novel-backups/" + result.get("filename")), eq(bytes));
        }
    }

    @Test void downloadDoesNotOverwriteRestoredMetadataWithStaleNovel() throws Exception {
        Novel before = novel("Before"); Novel after = novel("Restored"); byte[] bytes = {1, 2, 3};
        when(novels.findById(1L)).thenReturn(Optional.of(before), Optional.of(after));
        when(sardine.get("https://dav.example/novel-backups/test.backup.json")).thenReturn(new ByteArrayInputStream(bytes));
        try (MockedStatic<SardineFactory> factory = mockStatic(SardineFactory.class)) {
            factory.when(() -> SardineFactory.begin("user", "secret")).thenReturn(sardine);
            assertEquals(true, service.syncDownload(1L, "test.backup.json").get("success"));
            verify(bundles).restore(eq(1L), eq(bytes));
            verify(novels).save(same(after));
            verify(novels, never()).save(same(before));
        }
    }
}
