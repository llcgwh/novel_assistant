package com.novelwriting.service;

import static com.novelwriting.service.WritingDeskDocuments.*;
import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
    WritingDeskService.class,
    ManuscriptExportService.class,
  }
)
class WritingDeskServiceTest {

  @Autowired
  EntityManager em;

  @Autowired
  WritingService writing;

  @Autowired
  WritingDeskService desk;

  @Autowired
  ManuscriptExportService export;

  long novel() {
    Novel novel = new Novel();
    novel.setTitle("创作桌验证");
    em.persist(novel);
    em.flush();
    return novel.getId();
  }

  ObjectNode chapter(long novel, String title) {
    ObjectNode draft = JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("title", title);
    draft.set(
      "doc",
      parse(
        "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"潮声\"}]},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p2\"},\"content\":[{\"type\":\"text\",\"text\":\"来信\"}]}]}"
      )
    );
    return writing.create(novel, draft);
  }

  ObjectNode nextPen(String chapter, String block) {
    return anchor(chapter, block)
      .put("nextScene", "下一幕：灯塔")
      .put("question", "谁寄的信？")
      .put("opening", "明天从潮声写起")
      .put("updatedAt", "2026-09-28T12:00:00Z");
  }

  ObjectNode task(String chapter, String block) {
    return anchor(chapter, block)
      .put("uid", UUID.randomUUID().toString())
      .put("body", "创作备注不属于正文")
      .put("category", "dialogue")
      .put("priority", "high")
      .put("status", "open")
      .put("createdAt", "2026-09-28T12:00:00Z")
      .put("updatedAt", "2026-09-28T12:00:00Z");
  }

  ObjectNode bookmark(String chapter, String block) {
    return anchor(chapter, block)
      .put("uid", UUID.randomUUID().toString())
      .put("label", "回看此处")
      .put("createdAt", "2026-09-28T12:00:00Z");
  }

  ObjectNode anchor(String chapter, String block) {
    return JSON.createObjectNode()
      .put("chapterUid", chapter)
      .put("blockId", block)
      .put("excerpt", "留下原文摘录");
  }

  ObjectNode mutation(JsonNode state) {
    return ((ObjectNode) state.deepCopy()).put(
      "mutationId",
      UUID.randomUUID().toString()
    );
  }

  ObjectNode populated(String chapter) {
    ObjectNode state = emptyDesk();
    state.set("nextPen", nextPen(chapter, "p2"));
    state.withArray("tasks").add(task(chapter, "p2"));
    state.withArray("bookmarks").add(bookmark(chapter, "p2"));
    return mutation(state);
  }

  ObjectNode round(String uid) {
    return JSON.createObjectNode()
      .put("uid", uid)
      .put("title", "第二轮：人物与动机")
      .put("goal", "检查本轮人物行动的因果")
      .put("status", "active")
      .put("createdAt", "2026-09-28T12:00:00Z")
      .put("updatedAt", "2026-09-28T12:00:00Z");
  }

  ObjectNode withRound(ObjectNode state) {
    state.withArray("rounds").add(round("round-1"));
    ((ObjectNode) state.path("tasks").get(0)).put("roundUid", "round-1");
    return state;
  }

  @Test
  void missingLegacyColumnReadsEmptyAndWorkspaceDoesNotExposeCreativeContent() {
    long id = novel();
    ObjectNode chapter = chapter(id, "第一章");
    assertNull(writing.book(id).getDeskData());
    assertEquals(emptyDesk(), desk.get(id));
    desk.save(id, populated(chapter.path("uid").asText()));
    assertFalse(writing.workspace(id).has("desk"));
    assertFalse(writing.workspace(id).toString().contains("创作备注"));
    assertFalse(writing.summaries().toString().contains("创作备注"));
  }

  @Test
  void versionsAndMutationIdentityCannotBeUsedToOverwriteAnotherSave() {
    long id = novel();
    String uid = chapter(id, "原稿").path("uid").asText();
    long sequence = writing.workspace(id).path("changeSequence").asLong();
    ObjectNode request = populated(uid);
    ObjectNode saved = desk.save(id, request);
    assertEquals(1, saved.path("version").asLong());
    assertEquals(
      sequence + 1,
      writing.workspace(id).path("changeSequence").asLong()
    );
    assertEquals(saved, desk.save(id, request));
    assertEquals(
      sequence + 1,
      writing.workspace(id).path("changeSequence").asLong()
    );

    ObjectNode changed = request.deepCopy();
    ((ObjectNode) changed.path("nextPen")).put(
      "question",
      "伪造同一次请求的新内容"
    );
    assertConflict(() -> desk.save(id, changed));
    assertConflict(() -> desk.save(id, mutation(request)));
    assertConflict(() -> desk.save(id, request.deepCopy().put("version", 999)));
    assertEquals(saved, desk.get(id));

    ObjectNode second = mutation(saved);
    second.putNull("nextPen");
    assertEquals(2, desk.save(id, second).path("version").asLong());
    assertConflict(() -> desk.save(id, request));
  }

  @Test
  void legacyDocumentsAndSavesNormalizeRoundsWithoutLosingRetryIdentity() {
    long id = novel();
    String chapter = chapter(id, "旧稿").path("uid").asText();
    ObjectNode oldStored = populated(chapter);
    oldStored.remove("rounds");
    writing.book(id).setDeskData(stringify(oldStored));
    assertTrue(desk.get(id).path("rounds").isArray());
    assertTrue(desk.get(id).path("rounds").isEmpty());

    ObjectNode legacy = mutation(oldStored);
    ObjectNode saved = desk.save(id, legacy);
    assertEquals(saved, desk.save(id, legacy));
    assertEquals(1, saved.path("version").asLong());
    assertTrue(saved.path("rounds").isArray());

    ObjectNode request = withRound(mutation(saved));
    ObjectNode grouped = desk.save(id, request);
    assertEquals(grouped, desk.save(id, request));
    long sequence = writing.workspace(id).path("changeSequence").asLong();
    ObjectNode oldClient = mutation(grouped);
    oldClient.remove("rounds");
    ((ObjectNode) oldClient.path("tasks").get(0)).remove("roundUid");
    ResponseStatusException rejected = assertThrows(
      ResponseStatusException.class,
      () -> desk.save(id, oldClient)
    );
    assertEquals(409, rejected.getStatusCode().value());
    assertTrue(rejected.getReason().contains("未包含修订轮次"));
    assertEquals(grouped, desk.get(id));
    assertEquals(
      sequence,
      writing.workspace(id).path("changeSequence").asLong()
    );
  }

  @Test
  void roundsCanBeArchivedButCannotBeDeletedWhileTasksStillReferenceThem() {
    long id = novel();
    String chapter = chapter(id, "分轮修订").path("uid").asText();
    ObjectNode grouped = desk.save(id, withRound(populated(chapter)));
    ObjectNode archive = mutation(grouped);
    ((ObjectNode) archive.path("rounds").get(0)).put("status", "archived");
    ObjectNode archived = desk.save(id, archive);
    assertEquals(grouped.path("tasks"), archived.path("tasks"));
    assertEquals(
      "archived",
      archived.path("rounds").get(0).path("status").asText()
    );
    assertEquals(archived, desk.save(id, archive));

    ObjectNode dangling = mutation(archived);
    dangling.withArray("rounds").removeAll();
    assertBad(() -> desk.save(id, dangling));
    assertEquals(archived, desk.get(id));

    ObjectNode clear = mutation(archived);
    clear.withArray("rounds").removeAll();
    ((ObjectNode) clear.path("tasks").get(0)).put("roundUid", "");
    ObjectNode ungrouped = desk.save(id, clear);
    assertTrue(ungrouped.path("rounds").isEmpty());
    assertEquals(1, ungrouped.path("tasks").size());
  }

  @Test
  void roundsAreScopedToTheDeskEvenWhenTheirIdsExistInAnotherNovel() {
    long source = novel(),
      target = novel();
    desk.save(
      source,
      withRound(populated(chapter(source, "甲").path("uid").asText()))
    );
    ObjectNode foreign = populated(chapter(target, "乙").path("uid").asText());
    ((ObjectNode) foreign.path("tasks").get(0)).put("roundUid", "round-1");
    assertBad(() -> desk.save(target, foreign));
    assertEquals(emptyDesk(), desk.get(target));
  }

  @Test
  void notesAreIsolatedAndCannotAcquireForeignChapterAnchors() {
    long a = novel(),
      b = novel();
    String own = chapter(a, "甲").path("uid").asText();
    String other = chapter(b, "乙").path("uid").asText();
    ObjectNode saved = desk.save(a, populated(own));
    assertEquals(emptyDesk(), desk.get(b));
    ObjectNode foreign = mutation(saved);
    ((ObjectNode) foreign.path("tasks").get(0)).put("chapterUid", other);
    assertBad(() -> desk.save(a, foreign));
    assertBad(() -> desk.save(b, populated(own)));
    assertThrows(ResponseStatusException.class, () -> desk.get(Long.MAX_VALUE));

    // Deleted paragraphs retain excerpts and chapter entry points.
    ObjectNode missingBlock = mutation(saved);
    ((ObjectNode) missingBlock.path("tasks").get(0)).put(
      "blockId",
      "no-longer-in-document"
    );
    assertEquals(
      "no-longer-in-document",
      desk.save(a, missingBlock).path("tasks").get(0).path("blockId").asText()
    );
  }

  @Test
  void backupRoundTripKeepsAnchorsAndInvalidatesPriorClientsAndRetryIdentity() {
    long source = novel(),
      target = novel();
    String chapter = chapter(source, "来信").path("uid").asText();
    ObjectNode initial = withRound(populated(chapter));
    ObjectNode saved = desk.save(source, initial);
    ObjectNode backup = writing.exportBackup(source);
    assertFalse(backup.path("desk").has("mutationId"));
    ((ObjectNode) backup.path("desk")).put("version", 999);
    WritingService.validateBackup(backup);
    writing.restoreBackup(target, backup, Map.of());
    assertEquals(content(saved), content(desk.get(target)));
    assertEquals(1, desk.get(target).path("version").asLong());
    assertEquals(
      chapter,
      writing.workspace(target).path("chapters").get(0).path("uid").asText()
    );
    assertFalse(desk.get(target).has("mutationId"));

    writing.restoreBackup(source, backup, Map.of());
    assertEquals(
      saved.path("version").asLong() + 1,
      desk.get(source).path("version").asLong()
    );
    assertConflict(() -> desk.save(source, initial));
    assertConflict(() -> desk.save(source, mutation(saved)));
    assertEquals(content(saved), content(desk.get(source)));
  }

  @Test
  void oldBackupWithoutDeskProtectsNotesAndStillWorksForEmptyTargets() {
    long id = novel();
    String chapter = chapter(id, "旧备份").path("uid").asText();
    ObjectNode saved = desk.save(id, populated(chapter));
    ObjectNode legacy = writing.exportBackup(id);
    legacy.remove("desk");
    assertDoesNotThrow(() -> WritingService.validateBackup(legacy));
    assertBad(() -> writing.restoreBackup(id, legacy, Map.of()));
    assertEquals(saved, desk.get(id));

    long fresh = novel();
    writing.restoreBackup(fresh, legacy, Map.of());
    assertEquals(content(emptyDesk()), content(desk.get(fresh)));
    assertEquals(
      chapter,
      writing.workspace(fresh).path("chapters").get(0).path("uid").asText()
    );

    ObjectNode clear = mutation(emptyDesk()).put(
      "version",
      saved.path("version").asLong()
    );
    ObjectNode cleared = desk.save(id, clear);
    writing.restoreBackup(id, legacy, Map.of());
    assertEquals(content(emptyDesk()), content(desk.get(id)));
    assertEquals(
      cleared.path("version").asLong() + 1,
      desk.get(id).path("version").asLong()
    );
    assertFalse(desk.get(id).has("mutationId"));
  }

  @Test
  void legacyBackupWithoutRoundsCannotClearExistingRoundData() {
    long source = novel(),
      target = novel();
    String chapter = chapter(source, "旧备份").path("uid").asText();
    ObjectNode saved = desk.save(source, withRound(populated(chapter)));
    ObjectNode legacy = writing.exportBackup(source);
    ObjectNode oldDesk = (ObjectNode) legacy.path("desk");
    oldDesk.remove("rounds");
    ((ObjectNode) oldDesk.path("tasks").get(0)).remove("roundUid");
    assertDoesNotThrow(() -> WritingService.validateBackup(legacy));
    ResponseStatusException blocked = assertThrows(
      ResponseStatusException.class,
      () -> writing.restoreBackup(source, legacy, Map.of())
    );
    assertEquals(400, blocked.getStatusCode().value());
    assertTrue(blocked.getReason().contains("旧备份不含修订轮次"));
    assertEquals(saved, desk.get(source));

    writing.restoreBackup(target, legacy, Map.of());
    assertTrue(desk.get(target).path("rounds").isEmpty());
    assertEquals(oldDesk.path("tasks"), desk.get(target).path("tasks"));
    assertEquals(oldDesk.path("bookmarks"), desk.get(target).path("bookmarks"));

    ObjectNode onlyRound = mutation(emptyDesk()).put(
      "version",
      saved.path("version").asLong()
    );
    onlyRound.withArray("rounds").add(round("round-only"));
    ObjectNode roundOnlySaved = desk.save(source, onlyRound);
    legacy.remove("desk");
    assertBad(() -> writing.restoreBackup(source, legacy, Map.of()));
    assertEquals(roundOnlySaved, desk.get(source));
  }

  @Test
  void splitMovesEveryAnchoredKindButKeepsChapterLevelAndMissingBlockNotes() {
    long id = novel();
    String source = chapter(id, "原章").path("uid").asText();
    ObjectNode request = withRound(populated(source));
    request
      .withArray("tasks")
      .add(task(source, ""))
      .add(task(source, "missing"));
    ObjectNode before = desk.save(id, request);
    String next = UUID.randomUUID().toString();
    ObjectNode split = JSON.createObjectNode()
      .put("revision", 0)
      .put("index", 1)
      .put("uid", next)
      .put("title", "后半章");
    writing.split(id, source, split);
    ObjectNode after = desk.get(id);
    assertEquals(next, after.path("nextPen").path("chapterUid").asText());
    assertEquals(next, after.path("tasks").get(0).path("chapterUid").asText());
    assertEquals(before.path("rounds"), after.path("rounds"));
    assertEquals(
      "round-1",
      after.path("tasks").get(0).path("roundUid").asText()
    );
    assertEquals(
      next,
      after.path("bookmarks").get(0).path("chapterUid").asText()
    );
    assertEquals(
      source,
      after.path("tasks").get(1).path("chapterUid").asText()
    );
    assertEquals(
      source,
      after.path("tasks").get(2).path("chapterUid").asText()
    );
    assertEquals(
      before.path("version").asLong() + 1,
      after.path("version").asLong()
    );
    assertFalse(after.has("mutationId"));
    writing.split(id, source, split);
    assertEquals(after, desk.get(id));
    assertConflict(() -> desk.save(id, mutation(before)));
  }

  @Test
  void mergeRemapsCollidingBlockAnchorsAndLeavesChapterLevelNotesOnTrashedSource() {
    long id = novel();
    String first = chapter(id, "甲章").path("uid").asText();
    String second = chapter(id, "乙章").path("uid").asText();
    ObjectNode request = withRound(populated(second));
    request.withArray("tasks").add(task(second, ""));
    desk.save(id, request);
    ObjectNode merged = writing.merge(
      id,
      first,
      JSON.createObjectNode()
        .put("revision", 0)
        .put("otherRevision", 0)
        .put("otherUid", second)
    );
    String newBlock = merged
      .path("doc")
      .path("content")
      .get(3)
      .path("attrs")
      .path("id")
      .asText();
    assertNotEquals("p2", newBlock);
    ObjectNode state = desk.get(id);
    assertEquals(request.path("rounds"), state.path("rounds"));
    assertEquals(
      "round-1",
      state.path("tasks").get(0).path("roundUid").asText()
    );
    for (JsonNode anchor : List.of(
      state.path("nextPen"),
      state.path("tasks").get(0),
      state.path("bookmarks").get(0)
    )) {
      assertEquals(first, anchor.path("chapterUid").asText());
      assertEquals(newBlock, anchor.path("blockId").asText());
    }
    assertEquals(
      second,
      state.path("tasks").get(1).path("chapterUid").asText()
    );
    assertTrue(writing.get(id, second).path("deleted").asBoolean());
  }

  @Test
  void creativeEntriesNeverCountAsProseOrAppearInManuscriptExport()
    throws Exception {
    long id = novel();
    ObjectNode chapter = chapter(id, "正文");
    desk.save(id, populated(chapter.path("uid").asText()));
    assertEquals(
      4,
      writing.workspace(id).path("chapters").get(0).path("wordCount").asInt()
    );
    assertEquals(
      4,
      writing.summaries().path(String.valueOf(id)).path("words").asInt()
    );
    String txt = new String(
      export.export(id, JSON.createObjectNode().put("format", "txt")),
      StandardCharsets.UTF_8
    );
    assertTrue(txt.contains("潮声"));
    assertFalse(txt.contains("创作备注"));
    assertFalse(txt.contains("下一幕"));
  }

  @Test
  void validationRejectsInvalidVersionsFieldsDuplicateIdsAndExcessivePayloads() {
    ObjectNode valid = populated(UUID.randomUUID().toString());
    assertBad(() -> validateDesk(valid.deepCopy().put("version", -1), true));
    assertBad(() -> validateDesk(valid.deepCopy().put("version", "0"), true));
    assertBad(() -> validateDesk(valid.deepCopy().put("mutationId", ""), true));
    assertBad(() ->
      validateDesk(valid.deepCopy().put("unknown", "discard me"), true)
    );
    ObjectNode wrongCategory = valid.deepCopy();
    ((ObjectNode) wrongCategory.path("tasks").get(0)).put(
      "category",
      "anything"
    );
    assertBad(() -> validateDesk(wrongCategory, true));
    ObjectNode tooLong = valid.deepCopy();
    ((ObjectNode) tooLong.path("tasks").get(0)).put("body", "字".repeat(4001));
    assertBad(() -> validateDesk(tooLong, true));
    ObjectNode excerpt = valid.deepCopy();
    ((ObjectNode) excerpt.path("nextPen")).put("excerpt", "字".repeat(1001));
    assertBad(() -> validateDesk(excerpt, true));
    ObjectNode nextPen = valid.deepCopy();
    ((ObjectNode) nextPen.path("nextPen")).put("nextScene", "字".repeat(3001));
    assertBad(() -> validateDesk(nextPen, true));
    ObjectNode duplicate = valid.deepCopy();
    duplicate.withArray("tasks").add(duplicate.path("tasks").get(0).deepCopy());
    assertBad(() -> validateDesk(duplicate, true));
    ObjectNode tooMany = valid.deepCopy();
    for (int i = 0; i < 1000; i++) tooMany
      .withArray("tasks")
      .add(task(UUID.randomUUID().toString(), ""));
    assertBad(() -> validateDesk(tooMany, true));
    ObjectNode tooLarge = valid.deepCopy();
    for (int i = 0; i < 200; i++) tooLarge
      .withArray("tasks")
      .add(
        task(UUID.randomUUID().toString(), "").put("body", "字".repeat(4000))
      );
    assertBad(() -> validateDesk(tooLarge, true));
  }

  @Test
  void validatesRoundBoundariesAndRejectsDuplicateOrDanglingReferences() {
    ObjectNode valid = withRound(populated(UUID.randomUUID().toString()));
    ObjectNode limit = valid.deepCopy();
    ((ObjectNode) limit.path("rounds").get(0)).put(
      "title",
      "字".repeat(120)
    ).put("goal", "字".repeat(1000));
    assertDoesNotThrow(() -> validateDesk(limit, true));
    ((ObjectNode) limit.path("rounds").get(0)).remove("goal");
    assertDoesNotThrow(() -> validateDesk(limit, true));
    ((ObjectNode) limit.path("rounds").get(0)).put("goal", "");
    assertDoesNotThrow(() -> validateDesk(limit, true));

    for (ObjectNode badRound : List.of(
      round("round-1").put("title", " "),
      round("round-1").put("title", "字".repeat(121)),
      round("round-1").put("goal", "字".repeat(1001)),
      round("round-1").putNull("goal"),
      round("round-1").put("status", "done"),
      round("round-1").put("createdAt", ""),
      round("round-1").put("updatedAt", "x".repeat(65)),
      round("round-1").put("uid", "x".repeat(101)),
      round("round-1").put("extra", "unknown")
    )) {
      ObjectNode bad = valid.deepCopy();
      bad.withArray("rounds").removeAll().add(badRound);
      assertBad(() -> validateDesk(bad, true));
    }
    ObjectNode duplicate = valid.deepCopy();
    duplicate.withArray("rounds").add(round("round-1"));
    assertBad(() -> validateDesk(duplicate, true));
    ObjectNode hundred = valid.deepCopy();
    for (int i = 2; i <= 100; i++) hundred
      .withArray("rounds")
      .add(round("round-" + i));
    assertDoesNotThrow(() -> validateDesk(hundred, true));
    hundred.withArray("rounds").add(round("round-101"));
    assertBad(() -> validateDesk(hundred, true));
    assertBad(() -> validateDesk(valid.deepCopy().putNull("rounds"), true));
    assertBad(() ->
      validateDesk(valid.deepCopy().put("rounds", "invalid"), true)
    );
    for (JsonNode invalid : List.of(
      TextNode.valueOf("missing"),
      TextNode.valueOf(" "),
      TextNode.valueOf("x".repeat(101)),
      NullNode.instance,
      IntNode.valueOf(1)
    )) {
      ObjectNode bad = valid.deepCopy();
      ((ObjectNode) bad.path("tasks").get(0)).set("roundUid", invalid);
      assertBad(() -> validateDesk(bad, true));
    }
  }

  void assertConflict(Runnable action) {
    assertEquals(
      409,
      assertThrows(ResponseStatusException.class, action::run)
        .getStatusCode()
        .value()
    );
  }

  void assertBad(Runnable action) {
    assertEquals(
      400,
      assertThrows(ResponseStatusException.class, action::run)
        .getStatusCode()
        .value()
    );
  }
}
