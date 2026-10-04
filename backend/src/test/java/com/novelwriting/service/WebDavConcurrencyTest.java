package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.github.sardine.Sardine;
import com.novelwriting.entity.Novel;
import jakarta.persistence.EntityManager;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({WebDavSyncService.class, BackupOperationService.class, com.novelwriting.security.TestCredentials.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebDavConcurrencyTest {
  @Autowired EntityManager em;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean WebDavSyncService sync;
  @MockitoBean BackupBundleService bundles;
  <T> T tx(java.util.function.Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
  @Test void finishingUploadOnlyChangesTimestampAndPreservesEditsMadeDuringNetworkTransfer() throws Exception {
    long id = tx(() -> { Novel n = new Novel(); n.setTitle("Before"); n.setWebdavServerUrl("https://dav.example/"); n.setWebdavUsername("user");
      n.setWebdavPassword("old-password"); em.persist(n); em.flush(); return n.getId(); });
    Sardine dav = mock(Sardine.class); doReturn(dav).when(sync).client("user", "old-password");
    byte[] bytes = {1, 2, 3}; when(bundles.exportBundle(id)).thenReturn(bytes); when(dav.exists(anyString())).thenReturn(true);
    when(dav.get(anyString())).thenReturn(new ByteArrayInputStream(bytes));
    doAnswer(a -> tx(() -> { Novel current = em.find(Novel.class, id); current.setTitle("Edited while uploading");
      current.setWebdavUsername("new-user"); current.setWebdavPassword("new-password"); return null; }))
      .when(dav).put(anyString(), any(InputStream.class), anyMap());
    assertEquals(true, sync.syncUpload(id).get("success"));
    tx(() -> { Novel result = em.find(Novel.class, id); assertEquals("Edited while uploading", result.getTitle());
      assertEquals("new-user", result.getWebdavUsername()); assertEquals("new-password", result.getWebdavPassword());
      assertNotNull(result.getLastWebdavSync()); return null; });
    tx(() -> { em.createQuery("delete from BackupOperation").executeUpdate(); em.remove(em.find(Novel.class, id)); return null; });
  }
}
