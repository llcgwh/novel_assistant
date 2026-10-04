package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.WritingBook;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Server-owned statistics. Writing-session counters remain a separate data set. */
public final class WritingStatsDocuments {

  static final int MAX_BYTES = 8 * 1024 * 1024;
  static final int MAX_DAYS = 36600;
  static final int MAX_RECEIPTS = 20000;
  private static final long MAX_NUMBER = 9_007_199_254_740_991L;

  private WritingStatsDocuments() {}

  public static ObjectNode emptyStats() {
    ObjectNode result = JSON.createObjectNode()
      .put("schemaVersion", 1)
      .put("version", 0L);
    result.putArray("days");
    result.putArray("focusReceipts");
    return result;
  }

  public static ObjectNode read(WritingBook book) {
    return read(book == null ? null : book.getStatsData());
  }

  public static ObjectNode read(String stored) {
    return stored == null ? emptyStats() : (ObjectNode) parse(stored);
  }

  public static ObjectNode publicStats(ObjectNode stats) {
    ObjectNode result = JSON.createObjectNode()
      .put("schemaVersion", stats.path("schemaVersion").asInt())
      .put("version", stats.path("version").asLong());
    result.set("days", stats.path("days").deepCopy());
    return result;
  }

  /** JavaScript getTimezoneOffset has the opposite sign from a UTC offset. */
  static LocalDate currentDate(JsonNode input) {
    if (!input.has("timezoneOffsetMinutes")) return LocalDate.now();
    long offset = integer(input, "timezoneOffsetMinutes", -840, 840);
    return Instant.now().minusSeconds(offset * 60)
      .atOffset(ZoneOffset.UTC).toLocalDate();
  }

  static LocalDate date(String value) {
    try {
      if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
      LocalDate result = LocalDate.parse(value);
      if (result.getYear() < 1) throw new IllegalArgumentException();
      return result;
    } catch (RuntimeException e) {
      throw WritingService.bad("统计日期无效");
    }
  }

  public static LocalDate activityDate(JsonNode input) {
    LocalDate today = currentDate(input);
    LocalDate result = input.has("date") ? date(input.path("date").asText()) : today;
    if (result.isAfter(today)) throw WritingService.bad("统计日期不能晚于当前本地日期");
    return result;
  }

  private static ObjectNode day(ObjectNode stats, String date) {
    for (JsonNode row : stats.path("days"))
      if (date.equals(row.path("date").asText())) return (ObjectNode) row;
    ArrayNode days = stats.withArray("days");
    if (days.size() >= MAX_DAYS) throw WritingService.bad("每日统计已达到 36600 日上限");
    ObjectNode row = days.addObject().put("date", date).putNull("goal")
      .put("revisionSaves", 0L).put("finalTransitions", 0L)
      .put("focusSeconds", 0L).put("focusCompleted", 0L);
    row.putArray("completedChapterUids");
    return row;
  }

  private static int goal(WritingBook book) {
    JsonNode value = parse(book.getPreferences()).path("dailyGoal");
    if (value.isMissingNode()) return 2000;
    return (int) Math.max(0, Math.min(1_000_000, value.asLong(2000)));
  }

  private static boolean snapshotGoal(
    WritingBook book, ObjectNode stats, LocalDate date, LocalDate today, boolean overwrite
  ) {
    if (!date.equals(today)) return false;
    ObjectNode row = day(stats, date.toString());
    int goal = goal(book);
    if ((!row.path("goal").isNull() && !overwrite) || row.path("goal").asInt(-1) == goal)
      return false;
    row.put("goal", goal);
    return true;
  }

  private static void increment(ObjectNode row, String field, long amount) {
    long value = row.path(field).asLong();
    if (amount < 0 || value > MAX_NUMBER - amount)
      throw WritingService.bad("统计数值已达到上限");
    row.put(field, value + amount);
  }

  private static void commit(WritingBook book, ObjectNode stats) {
    increment(stats, "version", 1);
    String stored = stringify(stats);
    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw WritingService.bad("创作统计超过 8 MiB 上限，记录尚未提交");
    book.setStatsData(stored);
  }

  public static boolean captureDay(WritingBook book, JsonNode input, boolean overwrite) {
    LocalDate date = activityDate(input);
    ObjectNode stats = read(book);
    if (!snapshotGoal(book, stats, date, currentDate(input), overwrite)) return false;
    commit(book, stats);
    return true;
  }

  public static void saved(
    WritingBook book, JsonNode input, String chapterUid,
    boolean textChanged, boolean enteredFinal
  ) {
    LocalDate date = activityDate(input);
    ObjectNode stats = read(book);
    boolean changed = snapshotGoal(book, stats, date, currentDate(input), false);
    if (textChanged || enteredFinal) {
      ObjectNode row = day(stats, date.toString());
      if (textChanged) increment(row, "revisionSaves", 1);
      if (enteredFinal) {
        increment(row, "finalTransitions", 1);
        ArrayNode chapters = row.withArray("completedChapterUids");
        boolean present = false;
        for (JsonNode uid : chapters) if (chapterUid.equals(uid.asText())) present = true;
        if (!present) chapters.add(chapterUid);
      }
      changed = true;
    }
    if (changed) commit(book, stats);
  }

  public static boolean focus(WritingBook book, String uid, JsonNode input) {
    WritingService.uuid(uid);
    if (!input.isObject() || !uid.equals(input.path("uid").asText()))
      throw WritingService.bad("专注记录标识无效");
    ObjectNode receipt = receipt(input);
    ObjectNode stats = read(book);
    for (JsonNode prior : stats.path("focusReceipts")) {
      if (!uid.equals(prior.path("uid").asText())) continue;
      // JSON parsing may narrow long values to int nodes; compare normalized values.
      if (!receipt(prior).equals(receipt)) throw new ResponseStatusException(
        HttpStatus.CONFLICT, "同一专注时段已提交不同结果，请保留本机记录并检查"
      );
      return false;
    }
    LocalDate today = currentDate(input);
    if (date(receipt.path("endedOn").asText()).isAfter(today))
      throw WritingService.bad("专注结束日期不能晚于当前本地日期");
    if (stats.path("focusReceipts").size() >= MAX_RECEIPTS)
      throw WritingService.bad("专注统计已达到 20000 条上限，记录尚未提交");
    receipt.path("secondsByDate").fields().forEachRemaining(entry -> {
      ObjectNode row = day(stats, entry.getKey());
      increment(row, "focusSeconds", entry.getValue().asLong());
      snapshotGoal(book, stats, date(entry.getKey()), today, false);
    });
    String endedOn = receipt.path("endedOn").asText();
    if (receipt.path("completed").asBoolean()) {
      increment(day(stats, endedOn), "focusCompleted", 1);
      snapshotGoal(book, stats, date(endedOn), today, false);
    }
    stats.withArray("focusReceipts").add(receipt);
    commit(book, stats);
    return true;
  }

  /** Normalize only immutable receipt fields; retry-time timezone context is not identity. */
  private static ObjectNode receipt(JsonNode input) {
    if (!input.isObject()) throw WritingService.bad("专注记录无效");
    String uid = WritingService.uuid(input.path("uid").asText());
    LocalDate ended = date(input.path("endedOn").asText());
    if (!input.path("completed").isBoolean()) throw WritingService.bad("专注完成状态无效");
    JsonNode seconds = input.path("secondsByDate");
    if (!seconds.isObject() || seconds.size() > 366)
      throw WritingService.bad("专注日期分段无效或超过 366 日");
    ObjectNode result = JSON.createObjectNode().put("uid", uid)
      .put("endedOn", ended.toString()).put("completed", input.path("completed").asBoolean());
    ObjectNode normalized = result.putObject("secondsByDate");
    long total = 0;
    Iterator<Map.Entry<String, JsonNode>> fields = seconds.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      LocalDate date = date(entry.getKey());
      if (date.isAfter(ended)) throw WritingService.bad("专注分段日期不能晚于结束日期");
      long amount = integer(seconds, entry.getKey(), 0, 86400);
      total += amount;
      if (amount > 0) normalized.put(date.toString(), amount);
    }
    if (total > 86400 || (result.path("completed").asBoolean() && total == 0))
      throw WritingService.bad("专注时长无效或超过 24 小时");
    return result;
  }

  public static boolean hasData(WritingBook book) {
    ObjectNode stats = read(book);
    return !stats.path("days").isEmpty() || !stats.path("focusReceipts").isEmpty();
  }

  public static void protectLegacyRestore(WritingBook book, JsonNode input) {
    if (!input.has("stats") && hasData(book)) throw WritingService.bad(
      "这个旧备份不含创作统计与目标历史。请恢复到一个新建作品，避免清除现有记录。"
    );
  }

  public static ObjectNode validateStats(JsonNode input) {
    if (input == null || !input.isObject() ||
      input.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw WritingService.bad("创作统计无效或超过 8 MiB");
    keys(input, Set.of("schemaVersion", "version", "days", "focusReceipts"));
    if (integer(input, "schemaVersion", 1, 1) != 1) throw WritingService.bad("统计版本无效");
    integer(input, "version", 0, MAX_NUMBER - 1);
    if (!input.path("days").isArray() || input.path("days").size() > MAX_DAYS ||
      !input.path("focusReceipts").isArray() || input.path("focusReceipts").size() > MAX_RECEIPTS)
      throw WritingService.bad("创作统计记录数量无效");
    Set<String> dates = new HashSet<>();
    Map<String, JsonNode> days = new HashMap<>();
    for (JsonNode row : input.path("days")) {
      keys(row, Set.of("date", "goal", "revisionSaves", "finalTransitions", "completedChapterUids", "focusSeconds", "focusCompleted"));
      String day = date(row.path("date").asText()).toString();
      if (!dates.add(day)) throw WritingService.bad("统计日期重复");
      if (!row.has("goal")) throw WritingService.bad("缺少历史目标快照");
      if (!row.path("goal").isNull()) integer(row, "goal", 0, 1_000_000);
      for (String field : List.of("revisionSaves", "finalTransitions", "focusSeconds", "focusCompleted"))
        integer(row, field, 0, MAX_NUMBER);
      if (!row.path("completedChapterUids").isArray()) throw WritingService.bad("定稿章节记录无效");
      Set<String> chapters = new HashSet<>();
      for (JsonNode uid : row.path("completedChapterUids")) {
        if (!chapters.add(WritingService.uuid(uid.asText()))) throw WritingService.bad("定稿章节标识重复");
      }
      if (chapters.size() > row.path("finalTransitions").asLong()) throw WritingService.bad("定稿转换数量无效");
      days.put(day, row);
    }
    Set<String> receipts = new HashSet<>();
    ArrayNode normalizedReceipts = JSON.createArrayNode();
    Map<String, Long> seconds = new HashMap<>(), completed = new HashMap<>();
    for (JsonNode inputReceipt : input.path("focusReceipts")) {
      keys(inputReceipt, Set.of("uid", "endedOn", "completed", "secondsByDate"));
      ObjectNode receipt = receipt(inputReceipt);
      normalizedReceipts.add(receipt);
      if (!receipts.add(receipt.path("uid").asText())) throw WritingService.bad("专注时段标识重复");
      receipt.path("secondsByDate").fields().forEachRemaining(entry ->
        seconds.merge(entry.getKey(), entry.getValue().asLong(), Long::sum)
      );
      if (receipt.path("completed").asBoolean()) completed.merge(receipt.path("endedOn").asText(), 1L, Long::sum);
    }
    for (String day : seconds.keySet()) if (!days.containsKey(day)) throw WritingService.bad("专注分段缺少每日聚合");
    for (String day : completed.keySet()) if (!days.containsKey(day)) throw WritingService.bad("专注完成缺少每日聚合");
    for (Map.Entry<String, JsonNode> entry : days.entrySet()) {
      if (entry.getValue().path("focusSeconds").asLong() != seconds.getOrDefault(entry.getKey(), 0L) ||
        entry.getValue().path("focusCompleted").asLong() != completed.getOrDefault(entry.getKey(), 0L))
        throw WritingService.bad("专注回执与每日统计不一致");
    }
    ObjectNode result = input.deepCopy();
    result.set("focusReceipts", normalizedReceipts);
    return result;
  }

  public static void replace(WritingBook book, JsonNode replacement) {
    ObjectNode stats = validateStats(replacement);
    stats.put("version", Math.max(read(book).path("version").asLong(), stats.path("version").asLong()));
    commit(book, stats);
  }

  private static long integer(JsonNode node, String key, long min, long max) {
    JsonNode value = node.path(key);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < min || value.asLong() > max)
      throw WritingService.bad("统计数值无效：" + key);
    return value.asLong();
  }

  private static void keys(JsonNode node, Set<String> allowed) {
    if (!node.isObject()) throw WritingService.bad("统计记录格式无效");
    node.fieldNames().forEachRemaining(key -> {
      if (!allowed.contains(key)) throw WritingService.bad("不支持的统计字段：" + key);
    });
  }
}
