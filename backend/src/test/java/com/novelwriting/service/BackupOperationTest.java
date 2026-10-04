package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;
import static com.novelwriting.service.WritingDocuments.*;
import com.novelwriting.entity.BackupOperation;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({BackupOperationService.class, com.novelwriting.security.TestCredentials.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackupOperationTest {
  @Autowired BackupOperationService operations;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EntityManager em;
  @AfterEach void clear() { new TransactionTemplate(transactions).execute(s -> em.createQuery("delete from BackupOperation").executeUpdate()); }
  @Test void failureAuditSurvivesBusinessRollbackAndDoesNotPersistSecrets() {
    assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(s -> {
      try { operations.run(999L, "CLOUD_PUSH", () -> { throw new java.io.IOException("https://secret.example/password=TOP_SECRET"); }); }
      catch (Exception e) { throw new IllegalStateException(e); }
      return null;
    }));
    var items = operations.history(999L, 30, null).path("items");
    assertEquals(1, items.size()); assertEquals("FAILED", items.get(0).path("status").asText());
    assertEquals("IOException", items.get(0).path("errorType").asText());
    assertFalse(items.toString().contains("TOP_SECRET")); assertFalse(items.toString().contains("secret.example"));
  }
  @Test void comparisonAuditNeverCopiesNovelTextOrCredentials() throws Exception {
    operations.run(888L, "CLOUD_PREVIEW", () -> parse("{\"revision\":\"r1\",\"chapters\":[{\"text\":\"private prose\"}],\"password\":\"secret\",\"serverUrl\":\"https://private.example\"}"));
    var row = operations.history(888L, 30, null).path("items").get(0);
    assertEquals("r1", row.path("details").path("revision").asText()); assertEquals(1, row.path("details").path("chaptersCount").asInt());
    assertFalse(row.toString().contains("private prose")); assertFalse(row.toString().contains("secret")); assertFalse(row.toString().contains("private.example"));
  }
  @Test void recordsHttpFailureAndPaginatesWithoutTrimmingOlderRows() throws Exception {
    for (int i = 0; i < 4; i++) operations.run(777L, "CLOUD_PUSH", () -> JSON.createObjectNode().put("file", "version.json"));
    assertThrows(com.github.sardine.impl.SardineException.class, () -> operations.run(777L, "CLOUD_PUSH", () -> { throw new com.github.sardine.impl.SardineException("secret", 401, "secret"); }));
    var first = operations.history(777L, 2, null); assertEquals(401, first.path("items").get(0).path("httpStatus").asInt());
    var second = operations.history(777L, 2, first.path("nextBefore").asLong()); var third = operations.history(777L, 2, second.path("nextBefore").asLong());
    assertEquals(1, third.path("items").size()); assertTrue(third.path("nextBefore").isNull());
    assertEquals(5, operations.history(777L, 100, null).path("items").size());
  }
}
