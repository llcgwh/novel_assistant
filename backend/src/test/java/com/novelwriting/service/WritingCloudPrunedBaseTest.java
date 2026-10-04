package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.github.sardine.*;
import com.novelwriting.entity.*;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({WritingCloudService.class, WritingService.class, BackupOperationService.class, com.novelwriting.security.TestCredentials.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WritingCloudPrunedBaseTest {
  @Autowired EntityManager em;
  @Autowired PlatformTransactionManager transactions;
  @Autowired WritingService writing;
  @Autowired BackupOperationService operations;
  @MockitoSpyBean WritingCloudService cloud;
  @MockitoBean BackupBundleService bundles;
  Sardine dav;
  long id, chapterId;
  String bookUid, dir;
  final String root = UUID.randomUUID().toString(), base = UUID.randomUUID().toString(), head = UUID.randomUUID().toString();
  final String document = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"kept\"},\"content\":[{\"type\":\"text\",\"text\":\"本机未上传的正文必须保持。\"}]}]}";
  <T> T tx(java.util.function.Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
  DavResource resource(String revision, String parent, String stamp) {
    DavResource resource = mock(DavResource.class);
    when(resource.getName()).thenReturn(stamp + "_" + revision + "_" + parent + ".ink.json");
    return resource;
  }
  @BeforeEach void prepare() throws Exception {
    id = tx(() -> { Novel novel = new Novel(); novel.setTitle("Isolated stale device"); novel.setWebdavServerUrl("https://dav.example/"); em.persist(novel); em.flush(); return novel.getId(); });
    bookUid = tx(() -> {
      WritingBook book = writing.book(id); book.setRemoteBase(base); book.setResolvedHeads(stringify(List.of(head)));
      book.setChangeSequence(5); book.setSyncedSequence(4);
      WritingChapter chapter = new WritingChapter(); chapter.setNovelId(id); chapter.setUid(UUID.randomUUID().toString()); chapter.setTitle("本机未上传章节");
      chapter.setDocument(document); em.persist(chapter); em.flush(); chapterId = chapter.getId(); return book.getUid();
    });
    dir = "https://dav.example/novel-backups/ink-" + bookUid + "/";
    dav = mock(Sardine.class); doReturn(dav).when(cloud).client(any()); when(dav.exists(anyString())).thenReturn(true);
    var versions = List.of(resource(root, "root", "20200101000001"), resource(head, root, "20200101000008"));
    when(dav.list(dir)).thenReturn(versions);
    when(bundles.exportBundle(id)).thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  @AfterEach void clean() { tx(() -> {
    em.createQuery("delete from BackupOperation").executeUpdate(); em.createQuery("delete from WritingChapter").executeUpdate();
    em.createQuery("delete from WritingBook").executeUpdate(); em.createQuery("delete from Novel").executeUpdate(); return null;
  }); }
  void assertOriginalKept() { tx(() -> { assertEquals(document, em.find(WritingChapter.class, chapterId).getDocument()); assertEquals(base, em.find(WritingBook.class, id).getRemoteBase()); return null; }); }

  @Test void rememberedResolutionCannotBypassADeletedBaseline() throws Exception {
    var error = assertThrows(ResponseStatusException.class, () -> cloud.push(id));
    assertEquals(409, error.getStatusCode().value()); assertTrue(error.getReason().contains("基线"));
    verify(dav, never()).put(anyString(), any(InputStream.class), anyMap()); assertOriginalKept();
    var audit = operations.history(id, 10, null).path("items").get(0);
    assertEquals("FAILED", audit.path("status").asText()); assertEquals("LIST", audit.path("stage").asText()); assertEquals(409, audit.path("httpStatus").asInt());
  }
  @Test void emptyRemoteDoesNotTreatAnExistingBaselineAsFirstUploadOrAlreadySynced() throws Exception {
    when(dav.list(dir)).thenReturn(List.of());
    tx(() -> { em.find(WritingBook.class, id).setSyncedSequence(5); return null; });
    assertEquals(409, assertThrows(ResponseStatusException.class, () -> cloud.push(id)).getStatusCode().value());
    verify(dav, never()).put(anyString(), any(InputStream.class), anyMap()); assertOriginalKept();
  }
  @Test void retainedHistoricalBaselineStillAllowsExplicitResolution() throws Exception {
    var versions = List.of(resource(root, "root", "20200101000001"), resource(base, root, "20200101000002"), resource(head, base, "20200101000008"));
    when(dav.list(dir)).thenReturn(versions);
    doAnswer(call -> {
      String file = call.getArgument(2); var match = WritingCloudService.FILE.matcher(file); assertTrue(match.matches());
      return JSON.createObjectNode().put("revision", match.group(2));
    }).when(cloud).download(eq(dav), eq(dir), anyString(), eq(bookUid));
    var result = cloud.push(id); assertTrue(result.path("file").asText().endsWith(".ink.json"));
    verify(dav, times(2)).put(anyString(), any(InputStream.class), eq(Map.of("If-None-Match", "*")));
    tx(() -> { assertEquals(document, em.find(WritingChapter.class, chapterId).getDocument()); assertNotEquals(base, em.find(WritingBook.class, id).getRemoteBase()); return null; });
  }
}
