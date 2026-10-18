package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.WritingChapter;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;

/** Author-recorded history. Narrative order never stands in for story chronology. */
public final class StoryStateDocuments {
  private StoryStateDocuments() {}
  private static final Set<String> COMMON = Set.of("uid", "storyOrder", "narrativeOrder", "source", "recordedAt", "retracted");
  public static ObjectNode empty() {
    ObjectNode value = JSON.createObjectNode().put("schemaVersion", 1);
    value.putArray("states"); value.putArray("secrets");
    return value;
  }
  public static boolean hasData(JsonNode desk) {
    return !desk.path("storyState").path("states").isEmpty() || !desk.path("storyState").path("secrets").isEmpty();
  }
  public static void validate(JsonNode value) {
    keys(value, Set.of("schemaVersion", "states", "secrets"));
    if (!value.path("schemaVersion").isIntegralNumber() || !value.path("schemaVersion").canConvertToInt() || value.path("schemaVersion").asInt() != 1)
      throw WritingService.bad("不支持的故事状态版本");
    entries(value, "states", 2000);
    for (JsonNode row : value.path("states")) {
      record(row, Set.of("characterId", "characterName", "field", "value"));
      person(row); text(row, "field", 100, false); text(row, "value", 4000, false);
    }
    entries(value, "secrets", 500);
    for (JsonNode secret : value.path("secrets")) {
      keys(secret, Set.of("uid", "title", "truth", "recordedAt", "archived", "knowledge", "reader"));
      text(secret, "title", 200, false); text(secret, "truth", 6000, false); timestamp(secret); bool(secret, "archived");
      entries(secret, "knowledge", 1000); entries(secret, "reader", 1000);
      for (JsonNode row : secret.path("knowledge")) {
        record(row, Set.of("characterId", "characterName", "state", "belief")); person(row);
        choice(row, "state", Set.of("know", "believe", "suspect", "unknown"));
        text(row, "belief", 4000, false);
      }
      for (JsonNode row : secret.path("reader")) {
        record(row, Set.of("state", "detail"));
        choice(row, "state", Set.of("hidden", "hinted", "revealed")); text(row, "detail", 4000, false);
      }
    }
    Set<String> recordIds = new HashSet<>();
    for (JsonNode row : records(value)) if (!recordIds.add(row.path("uid").asText())) throw WritingService.bad("故事状态历史标识重复");
  }
  private static void record(JsonNode row, Set<String> extra) {
    Set<String> allowed = new HashSet<>(COMMON); allowed.addAll(extra); keys(row, allowed);
    order(row, "storyOrder"); order(row, "narrativeOrder"); timestamp(row); bool(row, "retracted");
    JsonNode source = row.path("source");
    keys(source, Set.of("chapterUid", "blockId", "excerpt", "eventId", "eventTitle", "note"));
    String chapter = text(source, "chapterUid", 36, true), block = text(source, "blockId", 128, true);
    if (!chapter.isEmpty()) WritingService.uuid(chapter);
    if (chapter.isEmpty() && !block.isEmpty()) throw WritingService.bad("段落来源必须包含章节");
    text(source, "excerpt", 1000, true); id(source, "eventId"); text(source, "eventTitle", 200, true);
    if (!source.path("eventId").isNull() && source.path("eventTitle").asText().isBlank()) throw WritingService.bad("来源事件需要保留名称");
    String note = text(source, "note", 2000, true);
    if (chapter.isEmpty() && source.path("eventId").isNull() && source.path("eventTitle").asText().isBlank() && note.isBlank())
      throw WritingService.bad("请记录来源章节、事件或作者说明");
  }
  private static void person(JsonNode row) { id(row, "characterId"); text(row, "characterName", 100, false); }
  private static void id(JsonNode row, String key) {
    JsonNode v = row.path(key);
    if (!v.isNull() && (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() <= 0 || v.asLong() > 9_007_199_254_740_991L))
      throw WritingService.bad("故事状态引用无效：" + key);
  }
  private static void order(JsonNode row, String key) {
    JsonNode v = row.path(key);
    if (!v.isNull() && (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0 || v.asLong() > 9_007_199_254_740_991L))
      throw WritingService.bad("故事顺序和叙述顺序必须分别填写非负整数，或留空");
  }
  private static void timestamp(JsonNode row) {
    try { Instant.parse(text(row, "recordedAt", 64, false)); }
    catch (RuntimeException e) { throw WritingService.bad("故事状态记录时间无效"); }
  }
  private static void keys(JsonNode node, Set<String> allowed) {
    if (!node.isObject()) throw WritingService.bad("故事状态条目无效");
    node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw WritingService.bad("不支持的故事状态字段：" + key); });
  }
  private static void bool(JsonNode row, String key) { if (!row.path(key).isBoolean()) throw WritingService.bad("故事状态标记无效：" + key); }
  private static void choice(JsonNode row, String key, Set<String> values) { if (!values.contains(text(row, key, 32, false))) throw WritingService.bad("故事状态选项无效"); }
  private static String text(JsonNode row, String key, int max, boolean empty) {
    JsonNode v = row.path(key);
    if (!v.isTextual() || v.asText().length() > max || (!empty && v.asText().isBlank())) throw WritingService.bad("故事状态字段无效：" + key);
    return v.asText();
  }
  private static void entries(JsonNode value, String key, int max) {
    JsonNode rows = value.path(key);
    if (!rows.isArray() || rows.size() > max) throw WritingService.bad("故事状态条数超出限制：" + key);
    Set<String> ids = new HashSet<>();
    for (JsonNode row : rows) if (!ids.add(WritingService.uuid(text(row, "uid", 36, false)))) throw WritingService.bad("故事状态记录标识重复");
  }
  public static List<JsonNode> records(JsonNode value) {
    List<JsonNode> result = new ArrayList<>();
    value.path("states").forEach(result::add);
    for (JsonNode secret : value.path("secrets")) { secret.path("knowledge").forEach(result::add); secret.path("reader").forEach(result::add); }
    return result;
  }
  public static List<JsonNode> sources(JsonNode desk) { return records(desk.path("storyState")).stream().map(row -> row.path("source")).toList(); }
  /** Jackson otherwise changes small LongNodes into IntNodes after persistence. */
  public static void normalize(JsonNode value) {
    for (JsonNode row : records(value)) {
      for (String key : List.of("characterId", "storyOrder", "narrativeOrder")) {
        if (row.path(key).isIntegralNumber()) ((ObjectNode)row).put(key, row.path(key).asLong());
      }
      JsonNode source = row.path("source");
      if (source.path("eventId").isIntegralNumber()) ((ObjectNode)source).put("eventId", source.path("eventId").asLong());
    }
  }
  public static void validateChange(Long novelId, JsonNode old, JsonNode next, EntityManager em) {
    preserve(old.path("states"), next.path("states"), Set.of("retracted"));
    preserve(old.path("secrets"), next.path("secrets"), Set.of("archived", "knowledge", "reader"));
    Map<String, JsonNode> oldSecrets = indexed(old.path("secrets"));
    for (JsonNode secret : next.path("secrets")) {
      JsonNode prior = oldSecrets.get(secret.path("uid").asText());
      if (prior != null) for (String key : List.of("knowledge", "reader")) preserve(prior.path(key), secret.path(key), Set.of("retracted"));
    }
    Set<Long> people = new HashSet<>(em.createQuery("select id from Character where novelId=:id", Long.class).setParameter("id", novelId).getResultList());
    Set<Long> events = new HashSet<>(em.createQuery("select id from TimelineEvent where novelId=:id", Long.class).setParameter("id", novelId).getResultList());
    Map<String, WritingChapter> chapters = new HashMap<>();
    em.createQuery("from WritingChapter where novelId=:id", WritingChapter.class).setParameter("id", novelId).getResultList().forEach(c -> chapters.put(c.getUid(), c));
    Map<String, JsonNode> prior = indexed(records(old));
    for (JsonNode row : records(next)) {
      // Existing references survive deletion. All newly recorded references are checked.
      if (prior.containsKey(row.path("uid").asText())) continue;
      if (row.has("characterId") && !people.contains(row.path("characterId").asLong())) throw WritingService.bad("状态人物不属于当前作品");
      JsonNode source = row.path("source");
      if (!source.path("eventId").isNull() && !events.contains(source.path("eventId").asLong())) throw WritingService.bad("来源事件不属于当前作品");
      String chapter = source.path("chapterUid").asText(), block = source.path("blockId").asText();
      if (!chapter.isEmpty()) {
        WritingChapter c = chapters.get(chapter);
        if (c == null || c.isDeleted()) throw WritingService.bad("来源章节不属于当前作品或已删除");
        if (!block.isEmpty() && !parse(c.getDocument()).findValuesAsText("id").contains(block)) throw WritingService.bad("来源段落不存在，请重新选择");
      }
    }
  }
  private static Map<String, JsonNode> indexed(Iterable<JsonNode> rows) {
    Map<String, JsonNode> result = new HashMap<>(); for (JsonNode row : rows) result.put(row.path("uid").asText(), row); return result;
  }
  private static void preserve(JsonNode old, JsonNode next, Set<String> mutable) {
    Map<String, JsonNode> incoming = indexed(next);
    for (JsonNode row : old) {
      JsonNode found = incoming.get(row.path("uid").asText());
      if (found == null) throw WritingService.bad("历史记录不能删除；请撤回记录或归档秘密");
      ObjectNode a = row.deepCopy(), b = found.deepCopy(); a.remove(mutable); b.remove(mutable);
      if (!a.equals(b)) throw WritingService.bad("历史记录不能覆盖；请撤回后追加一条更正");
    }
  }
  public static ObjectNode remap(ObjectNode desk, Map<String, Map<Long, Long>> ids) {
    for (JsonNode row : records(desk.path("storyState"))) {
      if (row.has("characterId")) remapId((ObjectNode) row, "characterId", ids.getOrDefault("characters", Map.of()));
      remapId((ObjectNode) row.path("source"), "eventId", ids.getOrDefault("timeline", Map.of()));
    }
    return desk;
  }
  private static void remapId(ObjectNode row, String key, Map<Long, Long> ids) {
    Long mapped = ids.get(row.path(key).asLong());
    if (mapped == null) row.putNull(key); else row.put(key, mapped);
  }
}
