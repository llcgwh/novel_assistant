package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.sardine.*;
import com.novelwriting.entity.*;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({BackupMaintenanceService.class, BackupOperationService.class, WritingService.class, com.novelwriting.security.TestCredentials.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackupMaintenanceTest {
  @Autowired BackupMaintenanceService maintenance;
  @Autowired BackupOperationService operations;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EntityManager em;
  @MockitoBean WritingCloudService cloud;
  @MockitoBean BackupBundleService bundles;
  Sardine dav;
  long id;
  String uid = "10000000-0000-4000-8000-000000000001", dir = "https://dav.example/novel-backups/ink-" + uid + "/";
  Map<String, DavResource> remote;
  BackupRetentionPlanTest fixtures = new BackupRetentionPlanTest();
  <T> T tx(java.util.function.Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
  DavResource resource(String name) {
    DavResource r = mock(DavResource.class); when(r.getName()).thenReturn(name); when(r.getPath()).thenReturn("/novel-backups/ink-" + uid + "/" + name);
    when(r.getModified()).thenReturn(java.util.Date.from(Instant.parse("2020-01-01T00:00:00Z")));
    when(r.getContentLength()).thenReturn(100L); when(r.getEtag()).thenReturn("\"v1\""); return r;
  }
  @BeforeEach void prepare() throws Exception {
    id = tx(() -> { Novel n = new Novel(); n.setTitle("Test only"); em.persist(n); em.flush(); return n.getId(); });
    dav = mock(ConditionalDavClient.class); remote = new LinkedHashMap<>(); for (var entry : fixtures.chain()) remote.put(entry.file(), resource(entry.file()));
    when(cloud.config(id)).thenReturn(new WritingCloudService.Config("https://dav.example/", "user", "private-password"));
    when(cloud.bookUid(id)).thenReturn(uid); when(cloud.folder(any(), eq(uid))).thenReturn(dir); when(cloud.client(any())).thenReturn(dav);
    when(dav.exists(anyString())).thenReturn(true); when(dav.list(dir)).thenAnswer(a -> new ArrayList<>(remote.values()));
    when(dav.list("https://dav.example/novel-backups/")).thenReturn(List.of());
    when(dav.lock(dir)).thenReturn("urn:uuid:lock"); when(dav.refreshLock(eq(dir), anyString(), eq(dir))).thenReturn("urn:uuid:lock");
    when(dav.get(anyString())).thenAnswer(a -> new com.github.sardine.impl.io.ContentLengthInputStream(new ByteArrayInputStream("{}".getBytes()), 2L));
    when(cloud.download(eq(dav), eq(dir), anyString(), eq(uid))).thenReturn(JSON.createObjectNode().set("bundle", JSON.createObjectNode()));
    doAnswer(a -> { String url = a.getArgument(0); String name = url.substring(dir.length()); remote.put(name, resource(name)); return null; })
      .when(dav).put(anyString(), any(InputStream.class), anyMap());
    doAnswer(a -> { String url = a.getArgument(0); remote.remove(url.substring(dir.length())); return null; }).when(dav).delete(anyString(), anyMap());
    maintenance.retention(id, JSON.createObjectNode().put("keepLast", 2).put("keepDays", 1).put("version", 0));
  }
  @AfterEach void clean() { tx(() -> { em.createQuery("delete from BackupCleanup").executeUpdate(); em.createQuery("delete from BackupOperation").executeUpdate();
    em.createQuery("delete from BackupRetention").executeUpdate(); em.createQuery("delete from WritingBook").executeUpdate(); em.createQuery("delete from Novel").executeUpdate(); return null; }); }
  ObjectNode confirm(ObjectNode preview) { return JSON.createObjectNode().put("token", preview.path("token").asText()).put("requestId", UUID.randomUUID().toString()).put("confirmed", true); }
  @Test void confirmationIsDurableIdempotentAndPreservesRecoveryGraph() throws Exception {
    var preview = maintenance.preview(id); var request = confirm(preview); var result = maintenance.execute(id, request);
    assertEquals("SUCCEEDED", result.path("status").asText(), stringify(result)); assertEquals(5, result.path("deleted").size());
    assertEquals(stringify(result), stringify(maintenance.execute(id, request))); verify(dav, times(5)).delete(anyString(), anyMap());
    assertEquals(Set.of(fixtures.uid(8)), fixtures.heads(new ArrayList<>(remote.keySet())));
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, confirm(preview)));
  }
  @Test void changedInventoryRequiresFreshPreviewWithoutDeletingAnything() throws Exception {
    var preview = maintenance.preview(id); remote.put("unexpected.json", resource("unexpected.json"));
    assertEquals(409, assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, confirm(preview))).getStatusCode().value());
    verify(dav, never()).delete(anyString(), anyMap());
    assertEquals("FAILED", operations.history(id, 10, null).path("items").get(0).path("status").asText());
  }
  @Test void changedPolicyAndExpiredOrCancelledPlansCannotDelete() throws Exception {
    var stale = maintenance.preview(id); maintenance.retention(id, JSON.createObjectNode().put("keepLast", 3).put("keepDays", 1).put("version", 1));
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, confirm(stale)));
    var expired = maintenance.preview(id); tx(() -> { em.find(BackupCleanup.class, expired.path("token").asText()).setExpiresAt(Instant.now().minusSeconds(1)); return null; });
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, confirm(expired)));
    var cancelled = maintenance.preview(id); maintenance.cancel(id, cancelled.path("token").asText());
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, confirm(cancelled))); verify(dav, never()).delete(anyString(), anyMap());
  }
  @Test void unsupportedLockNeverFallsBackToUnsafeDelete() throws Exception {
    var preview = maintenance.preview(id); when(dav.lock(dir)).thenThrow(new com.github.sardine.impl.SardineException("unsupported", 405, "unsupported"));
    var result = maintenance.execute(id, confirm(preview)); assertEquals("FAILED", result.path("status").asText());
    assertEquals("LOCK", result.path("stage").asText()); verify(dav, never()).delete(anyString(), anyMap());
    assertEquals(405, operations.history(id, 10, null).path("items").get(0).path("httpStatus").asInt());
  }
  @Test void lostDeleteResponseIsUncertainAndNotRetriedByRepeatedConfirmation() throws Exception {
    var preview = maintenance.preview(id); var request = confirm(preview);
    doAnswer(a -> { String url = a.getArgument(0); remote.remove(url.substring(dir.length())); throw new java.net.SocketTimeoutException("private-password"); })
      .when(dav).delete(anyString(), anyMap());
    var result = maintenance.execute(id, request); assertEquals("PARTIAL", result.path("status").asText(), stringify(result)); assertEquals(1, result.path("uncertainFiles").size());
    assertEquals(stringify(result), stringify(maintenance.execute(id, request))); verify(dav, times(1)).delete(anyString(), anyMap());
    assertFalse(operations.history(id, 10, null).toString().contains("private-password"));
    assertEquals(Set.of(fixtures.uid(8)), fixtures.heads(new ArrayList<>(remote.keySet())));
  }
  @Test void damagedBaselineOrCandidatePreventsAnyDeletion() throws Exception {
    var preview = maintenance.preview(id); doThrow(new IOException("damaged image")).when(bundles).validateRecoveryBundle(any());
    assertEquals("FAILED", maintenance.execute(id, confirm(preview)).path("status").asText()); verify(dav, never()).delete(anyString(), anyMap());
  }
  @Test void barePropfindEtagRequiresMatchingStrongHeadAndSize() throws Exception {
    String file = fixtures.file(3, 2); when(remote.get(file).getEtag()).thenReturn("opaque-v1");
    ConditionalDavClient verified = (ConditionalDavClient) dav;
    when(verified.headMetadata(dir + file)).thenReturn(new ConditionalDavClient.HeadMetadata("\"opaque-v1\"", 100));
    var preview = maintenance.preview(id);
    assertTrue(preview.path("candidates").toString().contains(file));
    assertTrue(preview.path("candidates").toString().contains("opaque-v1"));
    when(verified.headMetadata(dir + file)).thenReturn(new ConditionalDavClient.HeadMetadata("\"different\"", 100));
    assertFalse(maintenance.preview(id).path("candidates").toString().contains(file));
    when(verified.headMetadata(dir + file)).thenReturn(new ConditionalDavClient.HeadMetadata("\"opaque-v1\"", 101));
    assertFalse(maintenance.preview(id).path("candidates").toString().contains(file));
  }
  @Test void damagedCandidateStopsBeforeAnyDeleteEvenWithValidRecoveryHead() throws Exception {
    var preview = maintenance.preview(id); doNothing().doThrow(new IOException("damaged candidate")).when(bundles).validateRecoveryBundle(any());
    assertEquals("FAILED", maintenance.execute(id, confirm(preview)).path("status").asText());
    verify(bundles, times(2)).validateRecoveryBundle(any()); verify(dav, never()).delete(anyString(), anyMap());
  }
  @Test void directoryNamedLikeResolvedMarkerCannotHideRecoveryHeads() throws Exception {
    String name = fixtures.uid(8) + "_" + fixtures.uid(7) + ".resolved";
    DavResource directory = resource(name); when(directory.isDirectory()).thenReturn(true);
    when(directory.getPath()).thenReturn("/novel-backups/ink-" + uid + "/" + name + "/"); remote.put(name, directory);
    var preview = maintenance.preview(id); doThrow(new IOException("damaged baseline")).when(bundles).validateRecoveryBundle(any());
    assertEquals("FAILED", maintenance.execute(id, confirm(preview)).path("status").asText());
    verify(bundles).validateRecoveryBundle(any()); verify(dav, never()).delete(anyString(), anyMap());
  }
  @Test void anotherNovelCannotUseAPlanAndExplicitConfirmationIsRequired() throws Exception {
    var preview = maintenance.preview(id); var request = confirm(preview);
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id + 1000, request));
    assertThrows(ResponseStatusException.class, () -> maintenance.execute(id, request.deepCopy().put("confirmed", false)));
    verify(dav, never()).delete(anyString(), anyMap());
  }
}
