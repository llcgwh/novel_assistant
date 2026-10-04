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
    @Mock BackupOperationService operations;
    @Spy @InjectMocks WebDavSyncService service;

    Novel novel(String title) {
        Novel novel = new Novel(); novel.setId(1L); novel.setTitle(title);
        novel.setWebdavServerUrl("https://dav.example/"); novel.setWebdavUsername("user"); novel.setWebdavPassword("secret");
        return novel;
    }

    @Test void uploadSendsImageBundle() throws Exception {
        Novel novel = novel("Story"); byte[] bytes = {1, 2, 3};
        when(novels.findById(1L)).thenReturn(Optional.of(novel));
        when(bundles.exportBundle(1L)).thenReturn(bytes);
        when(sardine.get(anyString())).thenReturn(new ByteArrayInputStream(bytes));
        {
            doReturn(sardine).when(service).client("user", "secret");
            var result = service.syncUpload(1L);
            assertEquals(true, result.get("success"));
            assertTrue(result.get("filename").toString().endsWith(".backup.json"));
            verify(sardine).put(eq("https://dav.example/novel-backups/" + result.get("filename")), any(java.io.InputStream.class), eq(java.util.Map.of("If-None-Match", "*")));
        }
    }

    @Test void downloadDoesNotOverwriteRestoredMetadataWithStaleNovel() throws Exception {
        Novel before = novel("Before"); Novel after = novel("Restored"); byte[] bytes = {1, 2, 3};
        when(novels.findById(1L)).thenReturn(Optional.of(before), Optional.of(after));
        when(bundles.restoreWithCopy(eq(1L), eq(bytes))).thenReturn(2L);
        when(sardine.get("https://dav.example/novel-backups/test.backup.json")).thenReturn(new ByteArrayInputStream(bytes));
        {
            doReturn(sardine).when(service).client("user", "secret");
            assertEquals(true, service.syncDownload(1L, "test.backup.json").get("success"));
            verify(bundles).restoreWithCopy(eq(1L), eq(bytes));
            verify(novels).updateLastWebdavSync(eq(1L), any(java.time.LocalDateTime.class));
            verify(novels, never()).save(any());
        }
    }
}
