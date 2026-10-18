package com.novelwriting.ai;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.Novel;
import com.novelwriting.entity.WritingBook;
import com.novelwriting.entity.WritingChapter;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, AiContextService.class})
class AiContextServiceTest {
  @Autowired EntityManager em;
  @Autowired AiContextService contexts;

  long novel(String title) {
    Novel novel = new Novel(); novel.setTitle(title); novel.setDescription("描述"); novel.setWebdavUsername("private-account");
    novel.setWebdavPassword("private-password"); em.persist(novel); em.flush();
    return novel.getId();
  }
  WritingChapter chapter(long novelId, int position, String body) {
    WritingChapter row = new WritingChapter(); row.setNovelId(novelId); row.setUid(UUID.randomUUID().toString());
    row.setTitle("第" + position + "章"); row.setPosition(position); row.setDocument(doc(body).toString());
    em.persist(row); em.flush(); return row;
  }
  ObjectNode doc(String text) {
    ObjectNode doc = JSON.createObjectNode().put("type", "doc");
    var paragraph = doc.putArray("content").addObject().put("type", "paragraph");
    paragraph.putObject("attrs").put("id", "p1");
    paragraph.putArray("content").addObject().put("type", "text").put("text", text);
    return doc;
  }
  com.novelwriting.entity.Character person(long novelId, String name) {
    var person = new com.novelwriting.entity.Character(); person.setNovelId(novelId); person.setName(name);
    person.setPersonality("谨慎"); person.setBackground("不应被 personality 摘要覆盖的经历"); person.setPortraitImage("private-image");
    em.persist(person); em.flush(); return person;
  }
  @Test void settingsAreCompleteScopedAndExcludeManuscriptCredentialsAndImages() {
    long id = novel("当前作品"), other = novel("其他作品");
    person(other, "另一作品人物"); var target = person(id, "原名"); chapter(id, 0, "不应发送的正文");
    ObjectNode input = JSON.createObjectNode().put("targetId", target.getId());
    input.putObject("targetDraft").put("name", "本机未存名字").put("apiKey", "do-not-send");
    var snapshot = contexts.capture(id, AiActions.require("character.name"), input, 100000);
    String text = snapshot.data().toString();
    assertTrue(text.contains("不应被 personality 摘要覆盖的经历")); assertTrue(text.contains("本机未存名字"));
    for (String forbidden : new String[]{"另一作品人物", "不应发送的正文", "private-account", "private-password", "private-image", "do-not-send"}) assertFalse(text.contains(forbidden), forbidden);
    assertEquals("settings", snapshot.coverage().path("mode").asText());
    assertEquals("selected-reference", snapshot.coverage().path("progress").asText());
    assertFalse(snapshot.coverage().path("limitations").toString().contains("按当前章节末尾推演"));
    assertEquals("原名", target.getName(), "Reading a local draft must not mutate stored canon");
  }
  @Test void rejectsCrossNovelTargetBeforeProviderCall() {
    long id = novel("本作"), other = novel("别作"); var person = person(other, "另一人物");
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("character.name"), JSON.createObjectNode().put("targetId", person.getId()), 100000));
  }
  @Test void completeManuscriptSeparatesFutureAndCapturesUnsavedDraftAndLedger() {
    long id = novel("进度测试");
    var previous = chapter(id, 0, "过去正文"); previous.setSummary("尚未实现的前章创作目标");
    var current = chapter(id, 1, "服务端旧正文"); chapter(id, 2, "之后已写正文");
    WritingChapter deleted = chapter(id, 3, "已删除正文"); deleted.setDeleted(true);
    WritingBook book = new WritingBook(); book.setNovelId(id); book.setUid(UUID.randomUUID().toString());
    book.setDeskData("{\"version\":1,\"ideas\":[],\"storyState\":{\"schemaVersion\":1,\"states\":[],\"secrets\":[{\"title\":\"人物未知秘密\"}]}}");
    em.persist(book);
    ObjectNode input = JSON.createObjectNode().put("chapterUid", current.getUid());
    ObjectNode draft = input.putObject("currentDraft").put("uid", current.getUid()).put("revision", current.getRevision())
      .put("title", current.getTitle()).put("summary", "本章目标").put("notes", "尚未执行的计划");
    draft.set("doc", doc("当前未保存正文")); draft.putArray("links");
    var snapshot = contexts.capture(id, AiActions.require("writing.inspiration"), input, 100000);
    String data = snapshot.data().toString();
    for (String wanted : new String[]{"过去正文", "当前未保存正文", "之后已写正文", "人物未知秘密", "尚未执行的计划"}) assertTrue(data.contains(wanted), wanted);
    assertFalse(data.contains("服务端旧正文")); assertFalse(data.contains("已删除正文"));
    assertEquals(1, snapshot.coverage().path("chaptersBefore").asInt()); assertEquals(1, snapshot.coverage().path("chaptersAfter").asInt());
    assertEquals("chapter-end", snapshot.coverage().path("progress").asText());
    assertTrue(snapshot.coverage().path("limitations").toString().contains("按当前章节末尾推演"));
    assertTrue(snapshot.coverage().path("includesUnsavedDraft").asBoolean());
    assertTrue(current.getDocument().contains("服务端旧正文"));
    var beforeContent = snapshot.data().path("manuscript").get(0).path("content");
    var currentContent = snapshot.data().path("manuscript").get(1).path("content");
    assertEquals("尚未实现的前章创作目标", beforeContent.path("chapterGoal").asText());
    assertEquals("本章目标", currentContent.path("chapterGoal").asText());
    assertFalse(beforeContent.has("summary")); assertFalse(currentContent.has("summary"));
    assertFalse(beforeContent.path("blocks").toString().contains("创作目标"));
  }
  @Test void staleDraftAndOverflowFailInsteadOfReadingSilentFallback() {
    long id = novel("冲突测试"); var chapter = chapter(id, 0, "足量正文".repeat(100));
    ObjectNode input = JSON.createObjectNode().put("chapterUid", chapter.getUid());
    input.putObject("currentDraft").put("uid", chapter.getUid()).put("revision", 99);
    var conflict = assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("writing.inspiration"), input, 100000));
    assertEquals(409, conflict.getStatusCode().value());
    input.remove("currentDraft");
    var overflow = assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("writing.inspiration"), input, 100));
    assertEquals(413, overflow.getStatusCode().value());
  }
  @Test void novelOrderFollowsVolumesNotModificationTime() {
    long id = novel("卷顺序"); var later = chapter(id, 0, "后卷"); later.setVolumeId("later");
    var current = chapter(id, 10, "前卷"); current.setVolumeId("first");
    WritingBook book = new WritingBook(); book.setNovelId(id); book.setUid(UUID.randomUUID().toString());
    book.setVolumes("[{\"uid\":\"first\"},{\"uid\":\"later\"}]"); em.persist(book);
    var snapshot = contexts.capture(id, AiActions.require("writing.inspiration"), JSON.createObjectNode().put("chapterUid", current.getUid()), 100000);
    assertEquals(0, snapshot.coverage().path("chaptersBefore").asInt()); assertEquals(1, snapshot.coverage().path("chaptersAfter").asInt());
  }
  @Test void sourceSnapshotIgnoresCaptureClockButTracksOtherSettingsAndLocalDraft() {
    long id = novel("快照测试"); var target = person(id, "目标人物"); var another = person(id, "其他人物");
    ObjectNode input = JSON.createObjectNode().put("targetId", target.getId());
    input.putObject("targetDraft").put("name", "未保存名字");
    var action = AiActions.require("character.name");
    var first = contexts.capture(id, action, input, 100000);
    assertEquals(first.hash(), contexts.capture(id, action, input, 100000).hash());
    another.setBackground("另一人物的经历变了");
    assertNotEquals(first.hash(), contexts.capture(id, action, input, 100000).hash());
    var second = contexts.capture(id, action, input, 100000);
    ((ObjectNode) input.path("targetDraft")).put("name", "又一次编辑");
    assertNotEquals(second.hash(), contexts.capture(id, action, input, 100000).hash());
  }
  @Test void localRelationshipReferencesCannotCrossNovels() {
    long id = novel("关系测试"), other = novel("别作"); var foreign = person(other, "别作人物");
    ObjectNode input = JSON.createObjectNode(); input.putObject("targetDraft").put("characterId1", String.valueOf(foreign.getId()));
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("relationship.review"), input, 100000));
  }
  @Test void settingsStripEveryNestedLedgerExcerptButKeepAuthorRecordsAndSourceAnchors() {
    long id = novel("账本摘录隔离"); var target = person(id, "人物"); var current = chapter(id, 0, "当前章正文");
    ObjectNode ledger = JSON.createObjectNode().put("schemaVersion", 1);
    var state = ledger.putArray("states").addObject().put("value", "作者记录：左臂受伤");
    state.putObject("source").put("chapterUid", current.getUid()).put("blockId", "p1").put("excerpt", "状态来源原文不外发").put("note", "作者的来源说明");
    var secret = ledger.putArray("secrets").addObject().put("title", "信件真相").put("truth", "作者明确记录的真相");
    secret.putArray("knowledge").addObject().put("state", "suspect").putObject("source")
      .put("chapterUid", current.getUid()).put("blockId", "p2").put("excerpt", "人物知情来源原文不外发");
    secret.putArray("reader").addObject().put("state", "hinted").putObject("source")
      .put("chapterUid", current.getUid()).put("blockId", "p3").put("excerpt", "读者知情来源原文不外发");
    ObjectNode desk = JSON.createObjectNode().put("version", 2); desk.set("storyState", ledger);
    WritingBook book = new WritingBook(); book.setNovelId(id); book.setUid(UUID.randomUUID().toString()); book.setDeskData(desk.toString()); em.persist(book);
    var settings = contexts.capture(id, AiActions.require("character.name"), JSON.createObjectNode().put("targetId", target.getId()), 100000);
    String payload = settings.data().toString();
    for (String excerpt : new String[]{"状态来源原文不外发", "人物知情来源原文不外发", "读者知情来源原文不外发", "当前章正文"}) assertFalse(payload.contains(excerpt), excerpt);
    for (String retained : new String[]{"作者记录：左臂受伤", "作者明确记录的真相", "作者的来源说明", current.getUid(), "p1", "p2", "p3"}) assertTrue(payload.contains(retained), retained);
    assertTrue(book.getDeskData().contains("读者知情来源原文不外发"), "Sanitizing a send copy must not erase saved quotations");
    var writing = contexts.capture(id, AiActions.require("writing.inspiration"), JSON.createObjectNode().put("chapterUid", current.getUid()), 100000);
    assertTrue(writing.data().toString().contains("人物知情来源原文不外发"), "Writing mode explicitly includes manuscript evidence");
  }
  @Test void localChapterGoalAcceptsTheWritingServiceLimitAndRejectsOnlyBeyondIt() {
    long id = novel("本章目标上限"); var current = chapter(id, 0, "正文");
    ObjectNode input = JSON.createObjectNode().put("chapterUid", current.getUid());
    ObjectNode draft = input.putObject("currentDraft").put("uid", current.getUid()).put("revision", current.getRevision())
      .put("title", current.getTitle()).put("summary", "目".repeat(10000)).put("notes", "");
    draft.set("doc", doc("当前稿")); draft.putArray("links");
    var snapshot = contexts.capture(id, AiActions.require("writing.inspiration"), input, 100000);
    assertEquals(10000, snapshot.data().path("manuscript").get(0).path("content").path("chapterGoal").asText().length());
    draft.put("summary", "目".repeat(10001));
    var failure = assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("writing.inspiration"), input, 100000));
    assertEquals(400, failure.getStatusCode().value());
  }
  @Test void completeFormAssociationsEnterTheDraftWithoutSavingAndInvalidateItsSnapshot() {
    long id = novel("未保存关联"); var person = person(id, "关联人物");
    var tag = new com.novelwriting.entity.Tag(); tag.setNovelId(id); tag.setName("契约"); em.persist(tag);
    var scene = new com.novelwriting.entity.Scene(); scene.setNovelId(id); scene.setName("守塔人的房间"); em.persist(scene);
    var location = new com.novelwriting.entity.MapLocation(); location.setNovelId(id); location.setName("旧灯塔"); em.persist(location);
    var entry = new com.novelwriting.entity.WorldviewEntry(); entry.setNovelId(id); entry.setName("信使契约"); entry.setCategory("society");
    entry.setContent("履约必须付出代价"); em.persist(entry); em.flush();
    var input = JSON.createObjectNode().put("targetId", entry.getId());
    var draft = input.putObject("targetDraft").put("name", "未保存契约名称").put("category", "magic")
      .put("content", "未保存规则，不能直接成为正史").put("entryImage", "private-draft-image");
    draft.putArray("tagIds").add(tag.getId()).add(tag.getId());
    draft.putArray("characterIds").add(String.valueOf(person.getId()));
    draft.putArray("sceneIds").add(scene.getId()); draft.putArray("mapLocationIds").add(location.getId());
    var first = contexts.capture(id, AiActions.require("worldview.content"), input, 100000);
    var target = first.data().path("target").get(0).path("content");
    assertEquals("未保存契约名称", target.path("name").asText()); assertEquals("magic", target.path("category").asText());
    assertEquals("未保存规则，不能直接成为正史", target.path("content").asText());
    assertEquals(1, target.path("tags").size()); assertEquals(tag.getId().longValue(), target.path("tags").get(0).asLong());
    assertEquals(person.getId().longValue(), target.path("characters").get(0).asLong());
    assertEquals(scene.getId().longValue(), target.path("scenes").get(0).asLong());
    assertEquals(location.getId().longValue(), target.path("mapLocations").get(0).asLong());
    assertFalse(first.data().toString().contains("private-draft-image"));
    assertTrue(entry.getCharacters().isEmpty()); assertTrue(entry.getTags().isEmpty()); assertEquals("信使契约", entry.getName());
    draft.putArray("tagIds");
    var changed = contexts.capture(id, AiActions.require("worldview.content"), input, 100000);
    assertNotEquals(first.hash(), changed.hash());
    assertTrue(changed.data().path("target").get(0).path("content").path("tags").isEmpty());
  }
  @Test void draftAssociationListsRejectForeignMissingAndMalformedReferences() {
    long id = novel("关联边界"), other = novel("别作"); var foreign = person(other, "别作人物");
    var foreignTag = new com.novelwriting.entity.Tag(); foreignTag.setNovelId(other); foreignTag.setName("别作标签"); em.persist(foreignTag); em.flush();
    var input = JSON.createObjectNode(); var draft = input.putObject("targetDraft");
    draft.putArray("characterIds").add(foreign.getId());
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("worldview.content"), input, 100000));
    draft.removeAll(); draft.putArray("tagIds").add(foreignTag.getId());
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("character.name"), input, 100000));
    for (String field : new String[]{"tagIds", "characterIds", "sceneIds", "mapLocationIds"}) {
      draft.removeAll(); draft.putArray(field).add(999999);
      assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("worldview.content"), input, 100000), field);
      draft.put(field, "not-an-array");
      assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("worldview.content"), input, 100000), field);
    }
  }
  @Test void referenceChapterListContainsOnlyOwnedActiveMetadataAndRespectsVolumeOrder() {
    long id = novel("参考目录"), other = novel("别作");
    var first = chapter(id, 8, "第一卷正文不在列表中"); first.setVolumeId("first"); first.setWordCount(12);
    var last = chapter(id, 0, "第二卷正文不在列表中"); last.setVolumeId("last"); last.setWordCount(14);
    chapter(other, 0, "其他作品正文"); var deleted = chapter(id, 1, "回收站正文"); deleted.setDeleted(true);
    WritingBook book = new WritingBook(); book.setNovelId(id); book.setUid(UUID.randomUUID().toString());
    book.setVolumes("[{\"uid\":\"first\"},{\"uid\":\"last\"}]"); em.persist(book); em.flush();
    // A metadata listing must not parse any document, even if a stored body cannot be read.
    first.setDocument("not a JSON body"); em.flush(); em.clear();
    var list = contexts.referenceChapters(id);
    assertEquals(10000, list.path("maxChars").asInt()); assertEquals(2, list.path("chapters").size());
    assertEquals(first.getUid(), list.path("chapters").get(0).path("uid").asText());
    assertEquals(last.getUid(), list.path("chapters").get(1).path("uid").asText());
    assertEquals(12, list.path("chapters").get(0).path("wordCount").asInt());
    for (var row : list.path("chapters")) assertEquals(3, row.size());
    assertFalse(list.toString().contains("正文")); assertFalse(list.toString().contains("not a JSON"));
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> contexts.referenceChapters(Long.MAX_VALUE)).getStatusCode().value());
  }

  @Test void settingsSendOnlyExplicitlySelectedSavedBodyAndNoNotesLinksOrUnselectedExcerpts() {
    long id = novel("仅选中正文"); var selected = chapter(id, 0, "明确勾选的正文");
    selected.setSummary("不要发送章目标"); selected.setNotes("不要发送私密便笺"); selected.setLinks("[{\"excerpt\":\"不要发送关联摘录\"}]");
    chapter(id, 1, "没有勾选的私密正文");
    var input = JSON.createObjectNode(); input.putArray("referenceChapterUids").add(selected.getUid());
    var snapshot = contexts.capture(id, AiActions.require("character.personality"), input, 100000);
    String payload = snapshot.data().toString();
    assertTrue(payload.contains("明确勾选的正文"));
    for (String forbidden : new String[]{"不要发送章目标", "不要发送私密便笺", "不要发送关联摘录", "没有勾选的私密正文"}) assertFalse(payload.contains(forbidden));
    assertFalse(snapshot.data().has("manuscript")); assertFalse(snapshot.data().has("authorPlans"));
    assertEquals(1, snapshot.data().path("selectedManuscript").size());
    assertEquals("settings", snapshot.coverage().path("mode").asText());
    assertEquals("selected-reference", snapshot.coverage().path("progress").asText());
    assertFalse(snapshot.coverage().path("limitations").toString().contains("按当前章节末尾推演"));
    assertEquals(1, snapshot.coverage().path("selectedChapterCount").asInt());
    assertEquals(count("明确勾选的正文"), snapshot.coverage().path("selectedManuscriptChars").asInt());
    assertTrue(snapshot.sourceIds().contains("chapter:" + selected.getUid()));
    assertFalse(snapshot.coverage().path("includesUnsavedDraft").asBoolean());
  }

  @Test void emptySelectionDoesNotReadEvenUnreadableBodiesAndRejectsImplicitDraftInjection() {
    long id = novel("默认不选"); var hidden = chapter(id, 0, "未选内容"); hidden.setDocument("not a JSON document");
    for (var input : java.util.List.of(JSON.createObjectNode(), JSON.createObjectNode().set("referenceChapterUids", JSON.createArrayNode()))) {
      var snapshot = contexts.capture(id, AiActions.require("scene.description"), input, 100000);
      assertTrue(snapshot.data().path("selectedManuscript").isEmpty());
      assertEquals(0, snapshot.coverage().path("selectedChapterCount").asInt());
      assertEquals(0, snapshot.coverage().path("selectedManuscriptChars").asInt());
      assertTrue(snapshot.sourceIds().stream().noneMatch(source -> source.startsWith("chapter:")));
    }
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("scene.description"), JSON.createObjectNode().put("chapterUid", hidden.getUid()), 100000));
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("scene.description"), JSON.createObjectNode().set("currentDraft", doc("注入的未保存正文")), 100000));
    assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("writing.inspiration"), JSON.createObjectNode().set("referenceChapterUids", JSON.createArrayNode()), 100000));
  }

  @Test void referenceSelectionRejectsDeletedForeignMissingDuplicateAndMalformedChapterIds() {
    long id = novel("选择边界"), other = novel("别作"); var owned = chapter(id, 0, "本作正文");
    var foreign = chapter(other, 0, "别作正文"); var deleted = chapter(id, 1, "回收站正文"); deleted.setDeleted(true);
    for (String uid : java.util.List.of(foreign.getUid(), deleted.getUid(), UUID.randomUUID().toString())) {
      var input = JSON.createObjectNode(); input.putArray("referenceChapterUids").add(owned.getUid()).add(uid);
      assertEquals(404, assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("worldview.content"), input, 100000)).getStatusCode().value());
    }
    for (var selection : java.util.List.of(JSON.createArrayNode().add(owned.getUid()).add(owned.getUid()), JSON.createArrayNode().add("bad-id"), JSON.createArrayNode().add(123), JSON.getNodeFactory().nullNode(), JSON.getNodeFactory().textNode("not a list"))) {
      var input = JSON.createObjectNode().set("referenceChapterUids", selection);
      assertEquals(400, assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("worldview.content"), input, 100000)).getStatusCode().value());
    }
  }

  @Test void selectedBodyLimitRecountsActualSavedWordsAndRejectsOverflowWithoutTruncation() {
    long id = novel("正文上限"); var first = chapter(id, 0, "甲".repeat(6000)); var second = chapter(id, 1, "乙".repeat(4000));
    first.setWordCount(0); second.setWordCount(0); // Cached counts must never authorize excess manuscript.
    var input = JSON.createObjectNode(); input.putArray("referenceChapterUids").add(first.getUid()).add(second.getUid());
    var exact = contexts.capture(id, AiActions.require("character.background"), input, 100000);
    assertEquals(10000, exact.coverage().path("selectedManuscriptChars").asInt());
    assertTrue(exact.data().toString().contains("乙".repeat(4000)));
    second.setDocument(doc("乙".repeat(4001)).toString());
    var overflow = assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("character.background"), input, 100000));
    assertEquals(413, overflow.getStatusCode().value()); assertTrue(overflow.getReason().contains("没有发送或截断"));
  }

  @Test void selectedWordBudgetUsesEditorCountingAndStillEnforcesActualContextSize() {
    long id = novel("字词计数"); String body = "山 river don't 𠮷 \n 水。";
    var selected = chapter(id, 0, body); var input = JSON.createObjectNode(); input.putArray("referenceChapterUids").add(selected.getUid());
    var snapshot = contexts.capture(id, AiActions.require("location.description"), input, 100000);
    assertEquals(count(body), snapshot.coverage().path("selectedManuscriptChars").asInt());
    selected.setDocument(doc("a".repeat(20000)).toString());
    assertEquals(1, count("a".repeat(20000)));
    assertEquals(413, assertThrows(ResponseStatusException.class, () -> contexts.capture(id, AiActions.require("location.description"), input, 10000)).getStatusCode().value());
  }

  @Test void selectedChapterChoiceContentAndRevisionArePartOfFreshnessButUnselectedBodyIsNot() {
    long id = novel("选章快照"); var first = chapter(id, 0, "第一章正文"); var second = chapter(id, 1, "第二章正文");
    var input = JSON.createObjectNode(); input.putArray("referenceChapterUids").add(first.getUid());
    var action = AiActions.require("character.personality");
    var before = contexts.capture(id, action, input, 100000);
    second.setDocument(doc("未选择正文改变不影响已送证据").toString());
    assertEquals(before.hash(), contexts.capture(id, action, input, 100000).hash());
    first.setRevision(first.getRevision() + 1);
    assertNotEquals(before.hash(), contexts.capture(id, action, input, 100000).hash());
    first.setRevision(first.getRevision() - 1); first.setDocument(doc("选中正文发生改变").toString());
    assertNotEquals(before.hash(), contexts.capture(id, action, input, 100000).hash());
    var changed = contexts.capture(id, action, input, 100000);
    input.putArray("referenceChapterUids").add(second.getUid());
    assertNotEquals(changed.hash(), contexts.capture(id, action, input, 100000).hash());
    var provider = org.mockito.Mockito.mock(AiProviderService.class);
    assertFalse(new AiSuggestionService(contexts, provider).contextCurrent(id, "character.personality", input, changed.hash()));
    org.mockito.Mockito.verifyNoInteractions(provider);
  }

}
