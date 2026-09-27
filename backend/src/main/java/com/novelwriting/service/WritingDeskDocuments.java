package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.WritingBook;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Creative notes stay separate from manuscript documents and their word counts. */
public final class WritingDeskDocuments {

  private static final long MAX_VERSION = 9_007_199_254_740_991L;
  private static final Set<String> CATEGORIES = Set.of(
    "dialogue",
    "pov",
    "wording",
    "setting",
    "plot",
    "other"
  );

  private WritingDeskDocuments() {}

  public static ObjectNode emptyDesk() {
    ObjectNode desk = JSON.createObjectNode()
      .put("version", 0L)
      .putNull("nextPen");
    desk.putArray("tasks");
    desk.putArray("bookmarks");
    return desk;
  }

  public static ObjectNode read(WritingBook book) {
    ObjectNode desk = book == null || book.getDeskData() == null
      ? emptyDesk()
      : (ObjectNode) parse(book.getDeskData());
    return desk.put("version", desk.path("version").asLong());
  }

  public static ObjectNode validateDesk(
    JsonNode input,
    boolean mutationRequired
  ) {
    if (
      input == null ||
      !input.isObject() ||
      input.toString().getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024
    ) {
      throw WritingService.bad("创作工作台数据无效或超过 2 MiB");
    }
    keys(
      input,
      Set.of("version", "nextPen", "tasks", "bookmarks", "mutationId")
    );
    JsonNode version = input.path("version");
    if (
      !version.isIntegralNumber() ||
      !version.canConvertToLong() ||
      version.asLong() < 0 ||
      version.asLong() > MAX_VERSION
    ) {
      throw WritingService.bad("创作工作台版本无效");
    }
    if (mutationRequired || input.has("mutationId")) string(
      input,
      "mutationId",
      100,
      false
    );
    if (!input.has("nextPen")) throw WritingService.bad("缺少下一笔便签");
    if (!input.path("nextPen").isNull()) {
      JsonNode next = input.path("nextPen");
      keys(
        next,
        Set.of(
          "chapterUid",
          "blockId",
          "excerpt",
          "nextScene",
          "question",
          "opening",
          "updatedAt"
        )
      );
      anchor(next);
      for (String key : List.of("nextScene", "question", "opening"))
        string(next, key, 3000, true);
      string(next, "updatedAt", 64, false);
    }
    entries(input, "tasks", 1000);
    for (JsonNode task : input.path("tasks")) {
      keys(
        task,
        Set.of(
          "uid",
          "chapterUid",
          "blockId",
          "excerpt",
          "body",
          "category",
          "priority",
          "status",
          "createdAt",
          "updatedAt"
        )
      );
      anchor(task);
      string(task, "body", 4000, false);
      choice(task, "category", CATEGORIES);
      choice(task, "priority", Set.of("normal", "high"));
      choice(task, "status", Set.of("open", "done"));
      string(task, "createdAt", 64, false);
      string(task, "updatedAt", 64, false);
    }
    entries(input, "bookmarks", 500);
    for (JsonNode bookmark : input.path("bookmarks")) {
      keys(
        bookmark,
        Set.of("uid", "chapterUid", "blockId", "excerpt", "label", "createdAt")
      );
      anchor(bookmark);
      string(bookmark, "label", 200, true);
      string(bookmark, "createdAt", 64, false);
    }
    return input.deepCopy();
  }

  private static void keys(JsonNode node, Set<String> allowed) {
    if (!node.isObject()) throw WritingService.bad("创作工作台条目无效");
    node
      .fieldNames()
      .forEachRemaining(key -> {
        if (!allowed.contains(key)) throw WritingService.bad(
          "不支持的创作工作台字段：" + key
        );
      });
  }

  private static void entries(JsonNode desk, String key, int limit) {
    JsonNode rows = desk.path(key);
    if (!rows.isArray() || rows.size() > limit) throw WritingService.bad(
      "创作工作台 " + key + " 超出条数限制"
    );
    Set<String> ids = new HashSet<>();
    for (JsonNode row : rows) {
      String uid = string(row, "uid", 100, false);
      if (!ids.add(uid)) throw WritingService.bad("创作工作台条目标识重复");
    }
  }

  private static void anchor(JsonNode row) {
    WritingService.uuid(string(row, "chapterUid", 36, false));
    string(row, "blockId", 128, true);
    string(row, "excerpt", 1000, true);
  }

  private static String string(
    JsonNode row,
    String key,
    int limit,
    boolean empty
  ) {
    JsonNode value = row.path(key);
    if (
      !value.isTextual() ||
      value.asText().length() > limit ||
      (!empty && value.asText().isBlank())
    ) {
      throw WritingService.bad("创作工作台字段无效：" + key);
    }
    return value.asText();
  }

  private static void choice(JsonNode row, String key, Set<String> allowed) {
    if (
      !allowed.contains(string(row, key, 32, false))
    ) throw WritingService.bad("创作工作台选项无效：" + key);
  }

  public static List<JsonNode> anchors(JsonNode desk) {
    List<JsonNode> rows = new ArrayList<>();
    if (desk.path("nextPen").isObject()) rows.add(desk.path("nextPen"));
    desk.path("tasks").forEach(rows::add);
    desk.path("bookmarks").forEach(rows::add);
    return rows;
  }

  public static ObjectNode content(JsonNode desk) {
    ObjectNode copy = desk.deepCopy();
    copy.remove(List.of("version", "mutationId"));
    return copy;
  }

  public static void replace(WritingBook book, ObjectNode desk) {
    long version = read(book).path("version").asLong();
    if (version >= MAX_VERSION) throw WritingService.bad(
      "创作工作台版本已超出范围"
    );
    desk.put("version", version + 1);
    desk.remove("mutationId");
    book.setDeskData(stringify(desk));
  }

  /** Only anchors on blocks actually moved follow a split/merge; chapter notes stay put. */
  public static void moveAnchors(
    WritingBook book,
    String from,
    String to,
    Set<String> blocks,
    Map<String, String> remap
  ) {
    ObjectNode desk = read(book);
    boolean changed = false;
    for (JsonNode anchor : anchors(desk)) {
      String block = anchor.path("blockId").asText();
      if (
        anchor.path("chapterUid").asText().equals(from) &&
        !block.isBlank() &&
        blocks.contains(block)
      ) {
        ((ObjectNode) anchor).put("chapterUid", to).put(
          "blockId",
          remap.getOrDefault(block, block)
        );
        changed = true;
      }
    }
    if (changed) replace(book, desk);
  }
}
