package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(
  showSql = false,
  properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
)
@Import(
  {
    com.novelwriting.security.TestCredentials.class,
    WritingService.class,
    NovelService.class,
    ManuscriptExportService.class,
  }
)
class WritingServiceTest {

  @Autowired
  NovelService novels;

  @Autowired
  EntityManager em;

  @Autowired
  WritingService writing;

  @Autowired
  ManuscriptExportService export;

  long novel() {
    Novel n = new Novel();
    n.setTitle("雾海 & 来信");
    em.persist(n);
    em.flush();
    return n.getId();
  }

  ObjectNode draft(String title) {
    ObjectNode n = JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("title", title);
    n.set(
      "doc",
      parse(
        "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"沈雾推开门。Hello world 2026\",\"marks\":[{\"type\":\"bold\"}]}]},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p2\"},\"content\":[{\"type\":\"text\",\"text\":\"灯亮了。\"}]}]}"
      )
    );
    return n;
  }

  ObjectNode mutation(ObjectNode n) {
    return n.deepCopy().put("mutationId", UUID.randomUUID().toString());
  }

  @Test
  void createRetriesAndConcurrentSavesCannotDuplicateOrOverwrite() {
    long id = novel();
    ObjectNode input = draft("开端"),
      first = writing.create(id, input);
    assertEquals(first, writing.create(id, input));
    assertEquals(1, writing.workspace(id).path("chapters").size());
    ObjectNode change = mutation(first).put("title", "新标题");
    ObjectNode saved = writing.save(id, first.path("uid").asText(), change);
    assertEquals(1, saved.path("revision").asInt());
    assertEquals(saved, writing.save(id, first.path("uid").asText(), change));
    assertEquals(
      409,
      assertThrows(ResponseStatusException.class, () ->
        writing.save(id, first.path("uid").asText(), mutation(first))
      )
        .getStatusCode()
        .value()
    );
    assertEquals(
      "新标题",
      writing.get(id, first.path("uid").asText()).path("title").asText()
    );
  }

  @Test
  void ownershipAppliesToChaptersAndEveryTypedReference() {
    long a = novel(),
      b = novel();
    ObjectNode first = writing.create(a, draft("A"));
    assertThrows(ResponseStatusException.class, () ->
      writing.get(b, first.path("uid").asText())
    );
    Character outsider = new Character();
    outsider.setNovelId(b);
    outsider.setName("另一部书的人");
    em.persist(outsider);
    em.flush();
    ObjectNode invalid = mutation(first);
    invalid
      .putArray("links")
      .addObject()
      .put("uid", UUID.randomUUID().toString())
      .put("type", "characters")
      .put("targetId", outsider.getId());
    assertThrows(ResponseStatusException.class, () ->
      writing.save(a, first.path("uid").asText(), invalid)
    );
  }

  @Test
  void splitAndMergeKeepParagraphAnchorsAndTrashTheMergedSource() {
    long id = novel();
    Character hero = new Character();
    hero.setNovelId(id);
    hero.setName("沈雾");
    em.persist(hero);
    em.flush();
    ObjectNode data = draft("开端");
    data
      .putArray("links")
      .addObject()
      .put("uid", UUID.randomUUID().toString())
      .put("type", "characters")
      .put("targetId", hero.getId())
      .put("blockId", "p2")
      .put("role", "reference");
    ObjectNode first = writing.create(id, data);
    String uid = first.path("uid").asText();
    ObjectNode second = writing.split(
      id,
      uid,
      JSON.createObjectNode()
        .put("revision", 0)
        .put("index", 1)
        .put("uid", UUID.randomUUID().toString())
        .put("title", "续篇")
    );
    assertEquals(0, writing.get(id, uid).path("links").size());
    assertEquals("p2", second.path("links").get(0).path("blockId").asText());
    ObjectNode merged = writing.merge(
      id,
      uid,
      JSON.createObjectNode()
        .put("revision", 1)
        .put("otherRevision", 0)
        .put("otherUid", second.path("uid").asText())
    );
    assertEquals(2, merged.path("doc").path("content").size());
    assertEquals(1, writing.backlinks(id, "characters", hero.getId()).size());
    assertTrue(
      writing.get(id, second.path("uid").asText()).path("deleted").asBoolean()
    );
    assertFalse(writing.revisions(id, uid).isEmpty());
  }

  @Test
  void structureRejectsMissingChaptersAndUsesVersionChecks() {
    long id = novel();
    ObjectNode chapter = writing.create(id, draft("第一章"));
    ObjectNode workspace = writing.workspace(id);
    ObjectNode invalid = workspace.deepCopy();
    invalid.putArray("chapters");
    assertThrows(ResponseStatusException.class, () ->
      writing.structure(id, invalid)
    );
    String volume = UUID.randomUUID().toString();
    workspace
      .withArray("volumes")
      .addObject()
      .put("uid", volume)
      .put("title", "第一卷");
    ((ObjectNode) workspace.path("chapters").get(0)).put("volumeId", volume);
    writing.structure(id, workspace);
    assertEquals(
      volume,
      writing.get(id, chapter.path("uid").asText()).path("volumeId").asText()
    );
    assertThrows(ResponseStatusException.class, () ->
      writing.structure(id, workspace)
    );
  }

  @Test
  void statisticsUpsertsAreIdempotentAndIgnoreLateUpdates() {
    long id = novel();
    String uid = UUID.randomUUID().toString();
    ObjectNode stats = JSON.createObjectNode()
      .put("uid", uid)
      .put("date", "2026-09-26")
      .put("sequence", 2)
      .put("typed", 12)
      .put("pasted", 2)
      .put("activeSeconds", 60)
      .put("peak", 10)
      .put("net", 8);
    writing.session(id, uid, stats);
    writing.session(id, uid, stats);
    writing.session(
      id,
      uid,
      stats.deepCopy().put("sequence", 1).put("typed", 1)
    );
    var sessions = writing.workspace(id).path("sessions");
    assertEquals(1, sessions.size());
    assertEquals(12, sessions.get(0).path("typed").asInt());
  }

  @Test
  void exportsAreUtf8AndDocxContainsRealWordPartsAndStyles() throws Exception {
    long id = novel();
    writing.create(id, draft("第一章"));
    String txt = new String(
      export.export(id, JSON.createObjectNode().put("format", "txt")),
      StandardCharsets.UTF_8
    );
    assertTrue(txt.contains("沈雾推开门"));
    Map<String, String> files = new HashMap<>();
    try (
      ZipInputStream zip = new ZipInputStream(
        new ByteArrayInputStream(
          export.export(
            id,
            JSON.createObjectNode().put("format", "docx").put("toc", true)
          )
        )
      )
    ) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) files.put(
        e.getName(),
        new String(zip.readAllBytes(), StandardCharsets.UTF_8)
      );
    }
    assertTrue(files.get("word/document.xml").contains("<w:b/>"));
    assertTrue(files.get("word/document.xml").contains("&amp;"));
    assertTrue(files.get("word/styles.xml").contains("Heading2"));
    for (String xml : files.values()) {
      var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setFeature(
        "http://apache.org/xml/features/disallow-doctype-decl",
        true
      );
      factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void unicodeCountingAndDocumentValidationAgreeOnBoundaries() {
    assertEquals(8, count("沈雾推开门。Hello world 2026"));
    assertEquals(6, count("Hi沈雾 hello世界"));
    assertThrows(IllegalArgumentException.class, () ->
      validate(parse("{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}"))
    );
    assertThrows(IllegalArgumentException.class, () ->
      validate(
        parse(
          "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"same\"}},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"same\"}}]}"
        )
      )
    );
  }

  @Test
  void deletingAWorkRemovesItsManuscriptsHistoryAndCounters() {
    long id = novel();
    writing.create(id, draft("旧稿"));
    String uid = UUID.randomUUID().toString();
    writing.session(
      id,
      uid,
      JSON.createObjectNode()
        .put("uid", uid)
        .put("date", "2026-09-26")
        .put("sequence", 1)
        .put("typed", 1)
        .put("pasted", 0)
        .put("net", 1)
        .put("activeSeconds", 1)
        .put("peak", 0)
    );
    novels.deleteNovel(id);
    em.flush();
    for (String entity : List.of(
      "WritingBook",
      "WritingChapter",
      "WritingRevision",
      "WritingSession"
    ))
      assertEquals(
        0L,
        em
          .createQuery(
            "select count(*) from " + entity + " where novelId=:id",
            Long.class
          )
          .setParameter("id", id)
          .getSingleResult()
      );
  }

  @Test
  void exportFollowsVolumeOrderAndKeepsBookNumberingForSelectedChapters()
    throws Exception {
    long id = novel();
    String a = UUID.randomUUID().toString(),
      b = UUID.randomUUID().toString();
    ObjectNode workspace = writing.workspace(id);
    workspace
      .withArray("volumes")
      .addObject()
      .put("uid", a)
      .put("title", "上卷");
    workspace
      .withArray("volumes")
      .addObject()
      .put("uid", b)
      .put("title", "下卷");
    writing.structure(id, workspace);
    ObjectNode second = writing.create(id, draft("海潮").put("volumeId", b));
    writing.create(id, draft("来信").put("volumeId", a));
    writing.create(id, draft("楔子").put("numbered", false));
    String all = new String(
      export.export(id, JSON.createObjectNode().put("format", "txt")),
      StandardCharsets.UTF_8
    );
    assertTrue(all.indexOf("楔子") < all.indexOf("上卷"));
    assertTrue(all.indexOf("上卷") < all.indexOf("下卷"));
    assertTrue(all.contains("第1章 来信"));
    assertTrue(all.contains("第2章 海潮"));
    ObjectNode selection = JSON.createObjectNode().put("format", "txt");
    selection.putArray("uids").add(second.path("uid").asText());
    assertTrue(
      new String(export.export(id, selection), StandardCharsets.UTF_8).contains(
        "第2章 海潮"
      )
    );
  }
}
