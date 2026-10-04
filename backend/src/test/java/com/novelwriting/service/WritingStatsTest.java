package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.Novel;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
  { com.novelwriting.security.TestCredentials.class, WritingService.class }
)
class WritingStatsTest {

  @Autowired
  EntityManager em;

  @Autowired
  WritingService writing;

  long novel() {
    Novel novel = new Novel();
    novel.setTitle("统计验证");
    em.persist(novel);
    em.flush();
    return novel.getId();
  }

  String today() {
    return LocalDate.now(ZoneOffset.UTC).toString();
  }

  String yesterday() {
    return LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
  }

  ObjectNode date(String date) {
    return JSON.createObjectNode()
      .put("date", date)
      .put("timezoneOffsetMinutes", 0);
  }

  ObjectNode draft(String title) {
    ObjectNode draft = JSON.createObjectNode()
      .put("uid", UUID.randomUUID().toString())
      .put("title", title);
    draft.set(
      "doc",
      parse(
        "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"潮声\"}]},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p2\"},\"content\":[{\"type\":\"text\",\"text\":\"来信\"}]}]}"
      )
    );
    return draft;
  }

  ObjectNode mutation(ObjectNode chapter) {
    return chapter.deepCopy()
      .put("mutationId", UUID.randomUUID().toString())
      .put("date", today())
      .put("timezoneOffsetMinutes", 0);
  }

  ObjectNode replaceFirstText(ObjectNode chapter, String text) {
    ObjectNode input = mutation(chapter);
    ((ObjectNode) input.path("doc").path("content").get(0)
      .path("content").get(0)).put("text", text);
    return input;
  }

  ObjectNode save(long id, ObjectNode input) {
    return writing.save(id, input.path("uid").asText(), input);
  }

  ObjectNode goal(long id, int goal) {
    ObjectNode input = writing.workspace(id);
    input.put("date", today()).put("timezoneOffsetMinutes", 0);
    ((ObjectNode) input.path("preferences")).put("dailyGoal", goal);
    return writing.preferences(id, input);
  }

  JsonNode day(long id, String date) {
    for (JsonNode day : writing.stats(id).path("days")) {
      if (date.equals(day.path("date").asText())) return day;
    }
    return MissingNode.getInstance();
  }

  long total(long id, String field) {
    long sum = 0;
    for (JsonNode day : writing.stats(id).path("days")) {
      sum += day.path(field).asLong();
    }
    return sum;
  }

  ObjectNode focus(String uid, String endedOn, boolean completed) {
    ObjectNode input = JSON.createObjectNode()
      .put("uid", uid)
      .put("endedOn", endedOn)
      .put("completed", completed)
      .put("timezoneOffsetMinutes", 0);
    input.putObject("secondsByDate");
    return input;
  }

  void assertUnknownGoal(JsonNode day) {
    assertTrue(
      day.isMissingNode() || day.path("goal").isNull(),
      "A historical day without a captured goal must remain unknown"
    );
  }

  void assertNoActivity(long id) {
    for (String field : List.of(
      "revisionSaves", "finalTransitions", "focusSeconds", "focusCompleted"
    )) assertEquals(0, total(id, field), field);
  }

  void assertStatus(int status, Runnable action) {
    assertEquals(
      status,
      assertThrows(ResponseStatusException.class, action::run)
        .getStatusCode().value()
    );
  }

  @Test
  void publicStatsStartEmptyAndWorkspaceUsesTheSameDocument() {
    long id = novel();
    ObjectNode stats = writing.stats(id);
    assertEquals(1, stats.path("schemaVersion").asInt());
    assertTrue(stats.path("version").isIntegralNumber());
    assertTrue(stats.path("days").isArray());
    assertTrue(stats.path("days").isEmpty());
    assertFalse(stats.has("focusReceipts"));
    assertEquals(stats, writing.workspace(id).path("stats"));
  }

  @Test
  void equalLengthReplacementAndPureDeletionCountAsSeparateRevisionSaves() {
    long id = novel();
    ObjectNode original = writing.create(id, draft("正文修订"));
    ObjectNode replacement = replaceFirstText(original, "秋风");
    ObjectNode replaced = save(id, replacement);
    assertEquals(original.path("wordCount"), replaced.path("wordCount"));
    assertEquals(1, day(id, today()).path("revisionSaves").asInt());

    ObjectNode deletion = mutation(replaced);
    ((ArrayNode) deletion.path("doc").path("content")).remove(1);
    ObjectNode deleted = save(id, deletion);
    assertTrue(deleted.path("wordCount").asInt() < replaced.path("wordCount").asInt());
    assertEquals(2, day(id, today()).path("revisionSaves").asInt());
    assertEquals(0, day(id, today()).path("finalTransitions").asInt());

    ObjectNode beforeRetry = writing.stats(id);
    assertEquals(deleted, save(id, deletion));
    assertEquals(beforeRetry, writing.stats(id));
  }

  @Test
  void metadataFormattingNoOpsAndRejectedSavesDoNotCreateRevisionActivity() {
    long id = novel();
    ObjectNode original = writing.create(id, draft("原题"));
    ObjectNode metadata = mutation(original)
      .put("title", "新题")
      .put("summary", "改摘要")
      .put("notes", "写作备注")
      .put("goal", 800)
      .put("numbered", false);
    ObjectNode saved = save(id, metadata);
    ObjectNode formatting = mutation(saved);
    ((ObjectNode) formatting.path("doc").path("content").get(0)
      .path("content").get(0)).putArray("marks").addObject().put("type", "bold");
    saved = save(id, formatting);
    saved = save(id, mutation(saved));
    assertEquals(0, total(id, "revisionSaves"));

    ObjectNode beforeFailure = writing.stats(id);
    ObjectNode stale = replaceFirstText(original, "拒绝过期正文");
    assertStatus(409, () -> save(id, stale));
    assertEquals(beforeFailure, writing.stats(id));

    ObjectNode invalid = mutation(saved);
    invalid.set("doc", parse("{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}"));
    assertThrows(IllegalArgumentException.class, () -> save(id, invalid));
    assertEquals(beforeFailure, writing.stats(id));
  }

  @Test
  void finalTransitionsCountOncePerSaveAndCompletedChaptersAreDistinctPerDay() {
    long id = novel();
    ObjectNode chapter = writing.create(id, draft("定稿"));
    String uid = chapter.path("uid").asText();
    ObjectNode firstFinal = mutation(chapter).put("status", "final");
    chapter = save(id, firstFinal);
    ObjectNode afterFirst = writing.stats(id);
    save(id, firstFinal);
    assertEquals(afterFirst, writing.stats(id));

    chapter = save(id, mutation(chapter).put("title", "定稿标题"));
    assertEquals(1, day(id, today()).path("finalTransitions").asInt());
    chapter = save(id, mutation(chapter).put("status", "revision"));
    save(id, mutation(chapter).put("status", "final"));
    JsonNode today = day(id, today());
    assertEquals(2, today.path("finalTransitions").asInt());
    assertEquals(1, today.path("completedChapterUids").size());
    assertEquals(uid, today.path("completedChapterUids").get(0).asText());
    assertEquals(0, today.path("revisionSaves").asInt());

    ObjectNode other = writing.create(id, draft("另一章"));
    save(id, mutation(other).put("status", "final"));
    assertEquals(3, day(id, today()).path("finalTransitions").asInt());
    assertEquals(2, day(id, today()).path("completedChapterUids").size());
  }

  @Test
  void creatingDeletingAndRestoringFinalChaptersDoNotCountAsFinalTransitions() {
    long id = novel();
    writing.create(id, draft("创建即定稿").put("status", "final"));
    ObjectNode chapter = writing.create(id, draft("将要移入回收站"));
    chapter = save(id, mutation(chapter).put("deleted", true).put("status", "final"));
    chapter = save(id, mutation(chapter).put("status", "draft"));
    save(id, mutation(chapter).put("deleted", false).put("status", "final"));
    assertEquals(0, total(id, "finalTransitions"));
    assertEquals(0, total(id, "revisionSaves"));
  }

  @Test
  void currentDayGoalSnapshotIsIdempotentAndPreferencesOnlyChangeTodaysGoal() {
    long source = novel();
    goal(source, 700);
    writing.statsDay(source, date(today()));
    ObjectNode captured = writing.stats(source);
    writing.statsDay(source, date(today()));
    assertEquals(captured, writing.stats(source));
    assertEquals(700, day(source, today()).path("goal").asInt());

    // A restored known historical snapshot must not be rewritten with today's preference.
    ObjectNode backup = writing.exportBackup(source);
    ((ObjectNode) backup.path("stats").path("days").get(0)).put("date", yesterday());
    long target = novel();
    writing.restoreBackup(target, backup, Map.of());
    goal(target, 1200);
    writing.statsDay(target, date(today()));
    assertEquals(700, day(target, yesterday()).path("goal").asInt());
    assertEquals(1200, day(target, today()).path("goal").asInt());
    goal(target, 1800);
    assertEquals(700, day(target, yesterday()).path("goal").asInt());
    assertEquals(1800, day(target, today()).path("goal").asInt());
    assertNoActivity(target);
  }

  @Test
  void historicalSavesAndLegacySessionsDoNotInventHistoricalGoals() {
    long id = novel();
    goal(id, 2300);
    String legacyDate = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();
    String sessionUid = UUID.randomUUID().toString();
    writing.session(
      id, sessionUid,
      JSON.createObjectNode()
        .put("uid", sessionUid)
        .put("date", legacyDate)
        .put("sequence", 1)
        .put("typed", 30)
        .put("pasted", 2)
        .put("net", 25)
        .put("activeSeconds", 90)
        .put("peak", 20)
    );
    ObjectNode original = writing.create(id, draft("延迟同步"));
    save(id, replaceFirstText(original, "旧日修订").put("date", yesterday()));
    assertEquals(1, day(id, yesterday()).path("revisionSaves").asInt());
    assertUnknownGoal(day(id, yesterday()));
    assertUnknownGoal(day(id, legacyDate));
    assertEquals(2300, day(id, today()).path("goal").asInt());
    assertEquals(0, total(id, "focusSeconds"));
    assertEquals(0, total(id, "focusCompleted"));
    assertEquals(1, writing.workspace(id).path("sessions").size());
  }

  @Test
  void savesWithoutClientDateRemainCompatibleAndUseTheServerDay() {
    long id = novel();
    ObjectNode original = writing.create(id, draft("旧客户端"));
    ObjectNode input = replaceFirstText(original, "兼容保存");
    input.remove(List.of("date", "timezoneOffsetMinutes"));
    save(id, input);
    assertEquals(1, day(id, LocalDate.now().toString()).path("revisionSaves").asInt());
  }

  @Test
  void focusReceiptsSplitTimeAcrossDaysAndCancellationKeepsElapsedTime() {
    long id = novel();
    String completedUid = UUID.randomUUID().toString();
    ObjectNode completed = focus(completedUid, today(), true);
    ((ObjectNode) completed.path("secondsByDate"))
      .put(yesterday(), 40).put(today(), 80);
    writing.focus(id, completedUid, completed);
    assertEquals(40, day(id, yesterday()).path("focusSeconds").asInt());
    assertEquals(0, day(id, yesterday()).path("focusCompleted").asInt());
    assertUnknownGoal(day(id, yesterday()));
    assertEquals(80, day(id, today()).path("focusSeconds").asInt());
    assertEquals(1, day(id, today()).path("focusCompleted").asInt());

    String cancelledUid = UUID.randomUUID().toString();
    ObjectNode cancelled = focus(cancelledUid, today(), false);
    ((ObjectNode) cancelled.path("secondsByDate")).put(today(), 35);
    writing.focus(id, cancelledUid, cancelled);
    assertEquals(115, day(id, today()).path("focusSeconds").asInt());
    assertEquals(1, day(id, today()).path("focusCompleted").asInt());
    assertEquals(0, total(id, "revisionSaves"));
    assertEquals(0, total(id, "finalTransitions"));
  }

  @Test
  void duplicateFocusTerminalReceiptsAreIdempotentAndConflictingReceiptsAreRejected() {
    long id = novel();
    String uid = UUID.randomUUID().toString();
    ObjectNode input = focus(uid, today(), true);
    ((ObjectNode) input.path("secondsByDate")).put(yesterday(), 25).put(today(), 50);
    writing.focus(id, uid, input);
    ObjectNode before = writing.stats(id);
    writing.focus(id, uid, input.deepCopy());
    assertEquals(before, writing.stats(id));

    ObjectNode reordered = input.deepCopy();
    reordered.putObject("secondsByDate").put(today(), 50).put(yesterday(), 25);
    writing.focus(id, uid, reordered);
    assertEquals(before, writing.stats(id));

    ObjectNode changedSeconds = input.deepCopy();
    ((ObjectNode) changedSeconds.path("secondsByDate")).put(today(), 51);
    assertStatus(409, () -> writing.focus(id, uid, changedSeconds));
    assertStatus(409, () -> writing.focus(id, uid, input.deepCopy().put("completed", false)));
    ObjectNode changedEnd = focus(uid, yesterday(), true);
    ((ObjectNode) changedEnd.path("secondsByDate")).put(yesterday(), 25);
    assertStatus(409, () -> writing.focus(id, uid, changedEnd));
    assertEquals(before, writing.stats(id));
  }

  @Test
  void backupRoundTripPreservesFocusReceiptsAndDoesNotGenerateRestoreActivity() {
    long source = novel(), target = novel();
    ObjectNode original = writing.create(source, draft("备份正文"));
    save(source, replaceFirstText(original, "修订内容").put("status", "final"));
    String uid = UUID.randomUUID().toString();
    ObjectNode receipt = focus(uid, today(), true);
    ((ObjectNode) receipt.path("secondsByDate")).put(today(), 75);
    writing.focus(source, uid, receipt);
    ObjectNode before = writing.stats(source);
    ObjectNode backup = writing.exportBackup(source);
    assertTrue(backup.path("stats").has("focusReceipts"));
    assertEquals(1, backup.path("stats").path("focusReceipts").size());
    assertFalse(before.has("focusReceipts"));
    assertDoesNotThrow(() -> WritingService.validateBackup(backup));

    writing.restoreBackup(target, backup, Map.of());
    assertEquals(before.path("days"), writing.stats(target).path("days"));
    assertEquals(before.path("days"), writing.workspace(target).path("stats").path("days"));
    assertEquals(
      backup.path("stats").path("focusReceipts"),
      writing.exportBackup(target).path("stats").path("focusReceipts")
    );
    ObjectNode restored = writing.stats(target);
    writing.focus(target, uid, receipt);
    assertEquals(restored, writing.stats(target));
    assertStatus(409, () -> writing.focus(target, uid, receipt.deepCopy().put("completed", false)));

    writing.restoreBackup(target, backup, Map.of());
    assertEquals(before.path("days"), writing.stats(target).path("days"));
    assertEquals(1, total(target, "revisionSaves"));
    assertEquals(1, total(target, "finalTransitions"));
    assertEquals(75, total(target, "focusSeconds"));
    assertEquals(1, total(target, "focusCompleted"));
    assertEquals(before, writing.stats(source));
  }

  @Test
  void legacyBackupsCannotClearRecordedStatsButCanRestoreIntoAFreshNovel() {
    long source = novel();
    writing.create(source, draft("旧格式正文"));
    String uid = UUID.randomUUID().toString();
    ObjectNode receipt = focus(uid, today(), false);
    ((ObjectNode) receipt.path("secondsByDate")).put(today(), 40);
    writing.focus(source, uid, receipt);
    ObjectNode before = writing.stats(source);
    ObjectNode legacy = writing.exportBackup(source);
    legacy.remove("stats");
    assertDoesNotThrow(() -> WritingService.validateBackup(legacy));
    ObjectNode root = JSON.createObjectNode();
    root.set("writing", legacy);
    assertStatus(400, () -> writing.protectLegacyRestore(source, root));
    assertStatus(400, () -> writing.restoreBackup(source, legacy, Map.of()));
    assertEquals(before, writing.stats(source));

    long target = novel();
    writing.restoreBackup(target, legacy, Map.of());
    assertNoActivity(target);
    assertTrue(writing.stats(target).path("days").isEmpty());
    assertEquals(1, writing.workspace(target).path("chapters").size());
  }

  @Test
  void statsAndFocusRetryIdentityAreIsolatedByNovel() {
    long first = novel(), second = novel();
    ObjectNode original = writing.create(first, draft("首作"));
    save(first, replaceFirstText(original, "修订首作").put("status", "final"));
    goal(first, 900);
    goal(second, 1500);
    String uid = UUID.randomUUID().toString();
    ObjectNode firstReceipt = focus(uid, today(), true);
    ((ObjectNode) firstReceipt.path("secondsByDate")).put(today(), 60);
    writing.focus(first, uid, firstReceipt);
    ObjectNode secondReceipt = focus(uid, today(), false);
    ((ObjectNode) secondReceipt.path("secondsByDate")).put(today(), 20);
    writing.focus(second, uid, secondReceipt);

    assertEquals(1, total(first, "revisionSaves"));
    assertEquals(1, total(first, "finalTransitions"));
    assertEquals(60, total(first, "focusSeconds"));
    assertEquals(1, total(first, "focusCompleted"));
    assertEquals(900, day(first, today()).path("goal").asInt());
    assertEquals(0, total(second, "revisionSaves"));
    assertEquals(0, total(second, "finalTransitions"));
    assertEquals(20, total(second, "focusSeconds"));
    assertEquals(0, total(second, "focusCompleted"));
    assertEquals(1500, day(second, today()).path("goal").asInt());
  }

  @Test
  void creatingSplittingAndMergingChaptersDoNotGenerateRevisionActivity() {
    long id = novel();
    ObjectNode first = writing.create(id, draft("结构调整").put("status", "final"));
    String firstUid = first.path("uid").asText();
    ObjectNode second = writing.split(
      id, firstUid,
      JSON.createObjectNode()
        .put("revision", first.path("revision").asLong())
        .put("index", 1)
        .put("uid", UUID.randomUUID().toString())
        .put("title", "拆出的章节")
    );
    writing.merge(
      id, firstUid,
      JSON.createObjectNode()
        .put("revision", writing.get(id, firstUid).path("revision").asLong())
        .put("otherRevision", second.path("revision").asLong())
        .put("otherUid", second.path("uid").asText())
    );
    assertNoActivity(id);
  }

  @Test
  void daySnapshotsRejectFutureDatesAndInvalidTimezoneOffsetsWithoutChangingStats() {
    long id = novel();
    ObjectNode before = writing.stats(id);
    String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    assertStatus(400, () -> writing.statsDay(id, date(tomorrow)));
    for (int offset : List.of(-841, 841)) {
      assertStatus(
        400,
        () -> writing.statsDay(id, date(today()).put("timezoneOffsetMinutes", offset))
      );
    }
    assertStatus(
      400,
      () -> writing.statsDay(id, date(today()).put("timezoneOffsetMinutes", 0.5))
    );
    assertStatus(
      400,
      () -> writing.statsDay(id, date(today()).put("timezoneOffsetMinutes", "0"))
    );
    assertEquals(before, writing.stats(id));
  }

  @Test
  void delayedPriorDaySavesAndGoalRequestsRemainUnknownAfterCurrentGoalChanges() {
    long id = novel();
    goal(id, 1000);
    ObjectNode original = writing.create(id, draft("午夜前的本地草稿"));
    ObjectNode delayed = replaceFirstText(original, "跨日后同步的正文")
      .put("date", yesterday());
    ObjectNode saved = save(id, delayed);
    assertEquals(1, day(id, yesterday()).path("revisionSaves").asInt());
    assertUnknownGoal(day(id, yesterday()));

    goal(id, 2600);
    ObjectNode beforeRetry = writing.stats(id);
    assertEquals(saved, save(id, delayed));
    writing.statsDay(id, date(yesterday()));
    writing.statsDay(id, date(yesterday()));
    assertEquals(beforeRetry, writing.stats(id));
    assertEquals(1, day(id, yesterday()).path("revisionSaves").asInt());
    assertUnknownGoal(day(id, yesterday()));
    assertEquals(2600, day(id, today()).path("goal").asInt());
  }

  @Test
  void malformedFocusSegmentsAndMoreThanOneDayOfElapsedTimeAreRejectedAtomically() {
    long id = novel();
    String uid = UUID.randomUUID().toString();
    ObjectNode valid = focus(uid, today(), true);
    ((ObjectNode) valid.path("secondsByDate")).put(today(), 30);
    Map<String, ObjectNode> invalid = new LinkedHashMap<>();

    ObjectNode negative = valid.deepCopy();
    ((ObjectNode) negative.path("secondsByDate")).put(today(), -1);
    invalid.put("negative elapsed seconds", negative);
    ObjectNode fractional = valid.deepCopy();
    ((ObjectNode) fractional.path("secondsByDate")).put(today(), 0.5);
    invalid.put("fractional elapsed seconds", fractional);
    ObjectNode text = valid.deepCopy();
    ((ObjectNode) text.path("secondsByDate")).put(today(), "30");
    invalid.put("elapsed seconds must be numeric", text);
    ObjectNode afterEnd = valid.deepCopy();
    ((ObjectNode) afterEnd.path("secondsByDate"))
      .put(LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(), 10);
    invalid.put("segment after end date", afterEnd);
    ObjectNode invalidDate = valid.deepCopy();
    ((ObjectNode) invalidDate.path("secondsByDate"))
      .put(LocalDate.now(ZoneOffset.UTC).getYear() + "-02-30", 10);
    invalid.put("impossible segment date", invalidDate);
    ObjectNode tooLong = valid.deepCopy();
    tooLong.putObject("secondsByDate").put(yesterday(), 50000).put(today(), 50000);
    invalid.put("total elapsed exceeds 24 hours", tooLong);
    ObjectNode completedWithoutTime = valid.deepCopy();
    completedWithoutTime.putObject("secondsByDate");
    invalid.put("completed receipt has no elapsed time", completedWithoutTime);
    ObjectNode wrongShape = valid.deepCopy();
    wrongShape.putArray("secondsByDate").add(30);
    invalid.put("segments must be an object", wrongShape);

    ObjectNode before = writing.stats(id);
    invalid.forEach((label, input) -> assertAll(
      label,
      () -> assertStatus(400, () -> writing.focus(id, uid, input)),
      () -> assertEquals(before, writing.stats(id))
    ));
    assertEquals(0, writing.exportBackup(id).path("stats").path("focusReceipts").size());
    writing.focus(id, uid, valid);
    assertEquals(30, total(id, "focusSeconds"));
    assertEquals(1, total(id, "focusCompleted"));
  }

  @Test
  void backupRejectsMismatchedFocusAggregatesAndUnknownStatsFieldsBeforeRestore() {
    long source = novel(), target = novel();
    String uid = UUID.randomUUID().toString();
    ObjectNode receipt = focus(uid, today(), true);
    ((ObjectNode) receipt.path("secondsByDate")).put(today(), 60);
    writing.focus(source, uid, receipt);
    ObjectNode valid = writing.exportBackup(source);
    Map<String, ObjectNode> invalid = new LinkedHashMap<>();

    ObjectNode changedSeconds = valid.deepCopy();
    ((ObjectNode) changedSeconds.path("stats").path("days").get(0))
      .put("focusSeconds", 61);
    invalid.put("daily elapsed differs from receipts", changedSeconds);
    ObjectNode changedCompleted = valid.deepCopy();
    ((ObjectNode) changedCompleted.path("stats").path("days").get(0))
      .put("focusCompleted", 0);
    invalid.put("daily completion differs from receipts", changedCompleted);
    ObjectNode changedReceipt = valid.deepCopy();
    ((ObjectNode) changedReceipt.path("stats").path("focusReceipts").get(0)
      .path("secondsByDate")).put(today(), 70);
    invalid.put("receipt elapsed differs from daily aggregate", changedReceipt);
    ObjectNode missingReceipts = valid.deepCopy();
    ((ObjectNode) missingReceipts.path("stats")).putArray("focusReceipts");
    invalid.put("aggregates cannot silently drop receipts", missingReceipts);
    ObjectNode unknownRoot = valid.deepCopy();
    ((ObjectNode) unknownRoot.path("stats")).put("unrecognized", true);
    invalid.put("unknown stats field", unknownRoot);
    ObjectNode unknownDay = valid.deepCopy();
    ((ObjectNode) unknownDay.path("stats").path("days").get(0))
      .put("unrecognized", true);
    invalid.put("unknown day field", unknownDay);
    ObjectNode unknownReceipt = valid.deepCopy();
    ((ObjectNode) unknownReceipt.path("stats").path("focusReceipts").get(0))
      .put("unrecognized", true);
    invalid.put("unknown receipt field", unknownReceipt);

    ObjectNode sourceBefore = writing.stats(source), targetBefore = writing.stats(target);
    invalid.forEach((label, input) -> assertAll(
      label,
      () -> assertStatus(400, () -> WritingService.validateBackup(input)),
      () -> assertStatus(400, () -> writing.restoreBackup(target, input, Map.of())),
      () -> assertEquals(targetBefore, writing.stats(target)),
      () -> assertEquals(sourceBefore, writing.stats(source))
    ));
  }
}
