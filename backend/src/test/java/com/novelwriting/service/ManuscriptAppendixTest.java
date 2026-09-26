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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(
  showSql = false,
  properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
)
@Import(
  {
    com.novelwriting.security.TestCredentials.class,
    WritingService.class,
    ManuscriptExportService.class,
  }
)
class ManuscriptAppendixTest {

  @Autowired
  EntityManager em;

  @Autowired
  ManuscriptExportService export;

  private Long novel() {
    Novel novel = new Novel();
    novel.setTitle("雾港");
    em.persist(novel);
    return novel.getId();
  }

  private WritingChapter chapter(
    Long id,
    String title,
    String notes,
    ArrayNode links
  ) {
    WritingChapter chapter = new WritingChapter();
    chapter.setNovelId(id);
    chapter.setUid(UUID.randomUUID().toString());
    chapter.setTitle(title);
    chapter.setNotes(notes);
    ObjectNode doc = JSON.createObjectNode().put("type", "doc");
    doc
      .putArray("content")
      .addObject()
      .put("type", "paragraph")
      .putArray("content")
      .addObject()
      .put("type", "text")
      .put("text", "仅此一句正文。");
    chapter.setDocument(doc.toString());
    chapter.setLinks(links.toString());
    em.persist(chapter);
    return chapter;
  }

  private ObjectNode link(String type, Long id, String title, String role) {
    return JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("type", type)
      .put("targetId", id)
      .put("title", title)
      .put("role", role);
  }

  private String exported(
    Long id,
    String format,
    Boolean includeNotes,
    String... uids
  ) throws Exception {
    ObjectNode options = JSON.createObjectNode().put("format", format);
    if (includeNotes != null) options.put("includeNotes", includeNotes);
    for (String uid : uids) options.withArray("uids").add(uid);
    byte[] bytes = export.export(id, options);
    if (!format.equals("docx")) return new String(
      bytes,
      StandardCharsets.UTF_8
    );
    try (
      ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))
    ) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!entry.getName().equals("word/document.xml")) continue;
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature(
          "http://apache.org/xml/features/disallow-doctype-decl",
          true
        );
        return factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(zip.readAllBytes()))
          .getDocumentElement()
          .getTextContent();
      }
    }
    throw new AssertionError("DOCX has no document XML");
  }

  @ParameterizedTest
  @ValueSource(strings = { "txt", "md", "docx" })
  void appendixIncludesActualScopedCardsAndEveryResourceTypeOnlyWhenRequested(
    String format
  ) throws Exception {
    Long id = novel();
    Character person = new Character();
    person.setNovelId(id);
    person.setName("沈雾");
    person.setRole("守灯人");
    person.setDescription("从不解释来处");
    person.setPersonality("冷静又固执");
    person.setAppearance("银色发梢");
    person.setBackground("海潮留下的孩子");
    em.persist(person);
    Foreshadow clue = new Foreshadow();
    clue.setNovelId(id);
    clue.setTitle("旧钥匙");
    clue.setContent("钥匙能打开沉船的舱门");
    clue.setLaidAt("港口初遇");
    clue.setRevealedAt("风暴之后");
    em.persist(clue);
    Scene scene = new Scene();
    scene.setNovelId(id);
    scene.setName("灯塔");
    scene.setLocation("北岸");
    scene.setAtmosphere("潮湿的铜锈气味");
    scene.setDescription("窗户朝向旧航道");
    em.persist(scene);
    TimelineEvent event = new TimelineEvent();
    event.setNovelId(id);
    event.setTitle("归航");
    event.setEventTime("雾历十年");
    event.setRealOrder(7);
    event.setDescription("离岸的船重新出现");
    em.persist(event);
    Outline outline = new Outline();
    outline.setNovelId(id);
    outline.setTitle("海上抉择");
    outline.setContent("选择救人而非取回宝物");
    em.persist(outline);
    WorldviewEntry world = new WorldviewEntry();
    world.setNovelId(id);
    world.setName("雾潮");
    world.setCategory("history");
    world.setContent("月缺之夜海面升起白雾");
    em.persist(world);
    MapLocation location = new MapLocation();
    location.setNovelId(id);
    location.setName("外海");
    location.setDescription("沉船都朝着同一个方向");
    em.persist(location);
    Tag tag = new Tag();
    tag.setNovelId(id);
    tag.setName("旧航线");
    tag.setDescription("连接北岸与孤岛");
    em.persist(tag);
    ArrayNode links = JSON.createArrayNode()
      .add(
        link("characters", person.getId(), "过时的人物称呼", "viewpoint").put(
          "excerpt",
          "她看见远处的灯"
        )
      )
      .add(link("characters", person.getId(), "另一次出现", "reference"))
      .add(link("foreshadows", clue.getId(), "钥匙", "laid"))
      .add(link("scenes", scene.getId(), "灯塔", "reference"))
      .add(link("timeline", event.getId(), "归航", "current"))
      .add(link("outlines", outline.getId(), "抉择", "reference"))
      .add(link("worldview", world.getId(), "雾潮", "reference"))
      .add(link("map", location.getId(), "外海", "reference"))
      .add(link("tags", tag.getId(), "旧航线", "reference"));
    chapter(id, "初见", "注意灯的颜色\n保留第二处回声", links);
    String basic = exported(id, format, null);
    assertTrue(basic.contains("仅此一句正文"));
    assertFalse(basic.contains("创作附录"));
    assertFalse(basic.contains("海潮留下的孩子"));
    assertFalse(basic.contains("注意灯的颜色"));
    String result = exported(id, format, true);
    for (String expected : List.of(
      "创作附录",
      "人物卡 · 沈雾",
      "守灯人",
      "从不解释来处",
      "冷静又固执",
      "银色发梢",
      "海潮留下的孩子",
      "钥匙能打开沉船的舱门",
      "港口初遇",
      "风暴之后",
      "待回收",
      "潮湿的铜锈气味",
      "窗户朝向旧航道",
      "雾历十年",
      "离岸的船重新出现",
      "选择救人而非取回宝物",
      "月缺之夜海面升起白雾",
      "沉船都朝着同一个方向",
      "连接北岸与孤岛",
      "注意灯的颜色",
      "保留第二处回声",
      "视角人物、涉及",
      "她看见远处的灯"
    ))
      assertTrue(result.contains(expected), expected);
    assertEquals(1, result.split("人物卡 · 沈雾", -1).length - 1);
    assertTrue(result.indexOf("创作附录") > result.indexOf("仅此一句正文"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "txt", "md", "docx" })
  void missingOrForeignReferencesNeverLeakAnotherWorksContent(String format)
    throws Exception {
    Long local = novel(),
      other = novel();
    Character outsider = new Character();
    outsider.setNovelId(other);
    outsider.setName("其他作品私密姓名");
    outsider.setBackground("绝不能泄露的背景");
    em.persist(outsider);
    Character markedMissing = new Character();
    markedMissing.setNovelId(local);
    markedMissing.setName("重用的资料编号");
    markedMissing.setBackground("不应跟随旧链接导出");
    em.persist(markedMissing);
    ArrayNode links = JSON.createArrayNode()
      .add(link("characters", outsider.getId(), "旧人物引用", "reference"))
      .add(link("characters", Long.MAX_VALUE, "已经删除的档案", "reference"))
      .add(
        link(
          "characters",
          markedMissing.getId(),
          "恢复前的关联",
          "reference"
        ).put("missing", true)
      );
    chapter(local, "残留关联", "", links);
    String result = exported(local, format, true);
    assertFalse(result.contains("其他作品私密姓名"));
    assertFalse(result.contains("绝不能泄露的背景"));
    assertFalse(result.contains("不应跟随旧链接导出"));
    assertTrue(result.contains("旧人物引用"));
    assertTrue(result.contains("已经删除的档案"));
    assertTrue(result.contains("恢复前的关联"));
    assertEquals(3, result.split("未导出其内容", -1).length - 1);
  }

  @ParameterizedTest
  @ValueSource(strings = { "txt", "md", "docx" })
  void selectedChaptersExcludeOtherNotesAndTrashFromAppendix(String format)
    throws Exception {
    Long id = novel();
    var selected = chapter(
      id,
      "保留的篇目",
      "保留这一条笔记",
      JSON.createArrayNode()
    );
    chapter(id, "未选择的篇目", "未选择章节的秘密", JSON.createArrayNode());
    var trashed = chapter(
      id,
      "删除的篇目",
      "回收站中的秘密",
      JSON.createArrayNode()
    );
    trashed.setDeleted(true);
    String result = exported(id, format, true, selected.getUid());
    assertTrue(result.contains("保留这一条笔记"));
    assertFalse(result.contains("未选择章节的秘密"));
    assertFalse(result.contains("回收站中的秘密"));
    assertFalse(exported(id, format, false).contains("创作附录"));
  }
}
