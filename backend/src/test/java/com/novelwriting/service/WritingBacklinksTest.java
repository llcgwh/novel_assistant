package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(
  showSql = false,
  properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
)
@Import(
  { com.novelwriting.security.TestCredentials.class, WritingService.class }
)
class WritingBacklinksTest {

  @Autowired
  EntityManager em;

  @Autowired
  WritingService writing;

  @Test
  void appearancesFollowVolumeChapterAndParagraphOrderInsteadOfLinkCreationOrder() {
    Novel novel = new Novel();
    novel.setTitle("登场顺序");
    em.persist(novel);
    Character hero = new Character();
    hero.setNovelId(novel.getId());
    hero.setName("沈雾");
    em.persist(hero);
    String volumeA = UUID.randomUUID().toString(),
      volumeB = UUID.randomUUID().toString();
    ArrayNode volumes = JSON.createArrayNode();
    volumes.addObject().put("uid", volumeA).put("title", "上篇");
    volumes.addObject().put("uid", volumeB).put("title", "下篇");
    writing.book(novel.getId()).setVolumes(stringify(volumes));
    create(novel.getId(), hero.getId(), volumeB, "先创建的下篇", false);
    ObjectNode earlier = create(
      novel.getId(),
      hero.getId(),
      volumeA,
      "后创建的上篇",
      false
    );
    create(novel.getId(), hero.getId(), null, "回收站中的序章", true);
    ArrayNode links = writing.backlinks(
      novel.getId(),
      "characters",
      hero.getId()
    );
    assertEquals(6, links.size());
    assertEquals(earlier.path("uid"), links.get(0).path("chapterUid"));
    assertEquals("p1", links.get(0).path("blockId").asText());
    assertEquals("p2", links.get(1).path("blockId").asText());
    assertTrue(links.get(2).path("anchorMissing").asBoolean());
    assertEquals("先创建的下篇", links.get(3).path("chapterTitle").asText());
  }

  @Test
  void foreshadowPlanSurvivesSplitAndMergeAndValidatesItsCompletionRole() {
    Novel novel = new Novel();
    novel.setTitle("伏笔计划");
    em.persist(novel);
    Foreshadow clue = new Foreshadow();
    clue.setNovelId(novel.getId());
    clue.setTitle("潮汐的秘密");
    em.persist(clue);
    ObjectNode input = JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("title", "上章");
    ObjectNode doc = empty();
    ArrayNode blocks = doc.putArray("content");
    for (String block : new String[] { "p1", "p2" })
      blocks
        .addObject()
        .put("type", "paragraph")
        .putObject("attrs")
        .put("id", block);
    input.set("doc", doc);
    input
      .putArray("links")
      .addObject()
      .put("uid", UUID.randomUUID().toString())
      .put("type", "foreshadows")
      .put("targetId", clue.getId())
      .put("role", "reference")
      .put("plannedRole", "revealed")
      .put("blockId", "p2");
    ObjectNode first = writing.create(novel.getId(), input);
    String uid = first.path("uid").asText();
    ObjectNode second = writing.split(
      novel.getId(),
      uid,
      JSON.createObjectNode()
        .put("uid", UUID.randomUUID().toString())
        .put("title", "下章")
        .put("revision", 0)
        .put("index", 1)
    );
    assertEquals(
      "revealed",
      second.path("links").get(0).path("plannedRole").asText()
    );
    ObjectNode merged = writing.merge(
      novel.getId(),
      uid,
      JSON.createObjectNode()
        .put("otherUid", second.path("uid").asText())
        .put("revision", 1)
        .put("otherRevision", 0)
    );
    assertEquals(
      "reference",
      merged.path("links").get(0).path("role").asText()
    );
    assertEquals(
      "revealed",
      merged.path("links").get(0).path("plannedRole").asText()
    );
    ArrayNode invalid = merged.path("links").deepCopy();
    ((ObjectNode) invalid.get(0)).put("role", "developed");
    assertThrows(
      org.springframework.web.server.ResponseStatusException.class,
      () ->
        writing.validateLinks(novel.getId(), invalid, JSON.createArrayNode())
    );
    ((ObjectNode) merged.path("links").get(0)).put("role", "revealed");
    merged.put("mutationId", UUID.randomUUID().toString());
    assertEquals(
      "revealed",
      writing
        .save(novel.getId(), uid, merged)
        .path("links")
        .get(0)
        .path("role")
        .asText()
    );
  }

  private ObjectNode create(
    long novel,
    long character,
    String volume,
    String title,
    boolean deleted
  ) {
    ObjectNode input = JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("title", title)
      .put("volumeId", volume)
      .put("deleted", deleted);
    input.set(
      "doc",
      parse(
        "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"首次登场\"}]},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p2\"},\"content\":[{\"type\":\"text\",\"text\":\"再次登场\"}]}]}"
      )
    );
    ArrayNode links = input.putArray("links");
    for (String block : new String[] { "p2", "removed", "p1" })
      links
        .addObject()
        .put("uid", UUID.randomUUID().toString())
        .put("type", "characters")
        .put("targetId", character)
        .put("role", "reference")
        .put("blockId", block);
    return writing.create(novel, input);
  }
}
