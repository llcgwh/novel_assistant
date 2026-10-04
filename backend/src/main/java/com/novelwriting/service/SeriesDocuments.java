package com.novelwriting.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Pure, bounded validation for portable series documents. Never reads or writes storage. */
public final class SeriesDocuments {
  public static final ObjectMapper JSON = new ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  public static final int MAX_STATE_BYTES = 4 * 1024 * 1024;
  public static final int MAX_PACK_BYTES = 16 * 1024 * 1024;
  public static final int MAX_CATALOG_BYTES = 15 * 1024 * 1024;
  public static final int MAX_PAYLOAD_BYTES = 256 * 1024;
  public static final long MAX_COUNTER = 9_007_199_254_740_991L;
  private static final int MAX_GENERIC_BYTES = 32 * 1024 * 1024;
  private static final int MAX_DEPTH = 32;
  private static final Map<String, List<String>> FIELDS = Map.of(
    "calendar", List.of("name", "description", "notes", "epoch", "units", "dateFormat", "rules"),
    "location", List.of("name", "description", "notes", "geography", "environment", "culture"),
    "race", List.of("name", "description", "notes", "traits", "origins", "culture"),
    "organization", List.of("name", "description", "notes", "purpose", "structure", "rules"),
    "character", List.of("name", "description", "notes", "role", "personality", "appearance", "background")
  );
  private static final Set<String> REVISION_FIELDS = keys(
    "uid", "templateUid", "parentRevisionUid", "number", "kind", "seriesName", "payload",
    "authorStatus", "changeNote", "createdAt", "hash");
  private static final Set<String> SNAPSHOT_FIELDS = keys(
    "universeUid", "planet", "content", "authorStatus", "baselineRevisionUid", "lastReview",
    "fieldOrigins", "archived");
  private static final Set<String> COPY_FIELDS;
  static {
    Set<String> fields = new HashSet<>(SNAPSHOT_FIELDS);
    fields.addAll(keys("uid", "kind", "origin", "createdAt", "updatedAt", "history"));
    COPY_FIELDS = Set.copyOf(fields);
  }
  private SeriesDocuments() {}

  public static List<String> fields(String kind) {
    List<String> result = FIELDS.get(kind);
    if (result == null) throw invalid("母本类型无效");
    return result;
  }

  /** Call once and persist the returned state: these identities belong to this novel. */
  public static ObjectNode emptyState() {
    ObjectNode state = JSON.createObjectNode().put("schemaVersion", 1).put("version", 0L)
      .put("epoch", UUID.randomUUID().toString());
    state.putArray("worlds").addObject().put("uid", UUID.randomUUID().toString())
      .put("name", "本作世界").put("description", "");
    state.putArray("copies");
    state.putArray("sourceRevisions");
    return state;
  }

  public static ObjectNode validatePayload(String kind, JsonNode input) {
    bounded(input, MAX_PAYLOAD_BYTES, "母本内容");
    exact(input, new HashSet<>(fields(kind)), "母本内容");
    ObjectNode result = JSON.createObjectNode();
    for (String key : fields(kind)) {
      result.put(key, text(input, key, key.equals("name") ? 200 : 20_000, key.equals("name")));
    }
    bounded(result, MAX_PAYLOAD_BYTES, "母本内容");
    return result;
  }

  public static ObjectNode validateRevision(JsonNode input) {
    bounded(input, MAX_PAYLOAD_BYTES + 128 * 1024, "母本版本");
    exact(input, REVISION_FIELDS, "母本版本");
    String kind = kind(input);
    ObjectNode result = JSON.createObjectNode().put("uid", uid(input, "uid"))
      .put("templateUid", uid(input, "templateUid"));
    if (input.get("parentRevisionUid").isNull()) result.putNull("parentRevisionUid");
    else result.put("parentRevisionUid", uid(input, "parentRevisionUid"));
    result.put("number", counter(input, "number"));
    result.put("kind", kind).put("seriesName", text(input, "seriesName", 200, true));
    result.set("payload", validatePayload(kind, input.get("payload")));
    result.put("authorStatus", status(input)).put("changeNote", text(input, "changeNote", 20_000, false));
    result.put("createdAt", timestamp(input, "createdAt"));
    String suppliedHash = text(input, "hash", 64, false);
    if (!suppliedHash.matches("[0-9a-f]{64}") || !suppliedHash.equals(revisionHash(result)))
      throw invalid("母本版本校验值不匹配，内容或身份可能已改变");
    result.put("hash", suppliedHash);
    return result;
  }

  /** Hashes every immutable field, including identity, parent and time; omits only hash. */
  public static String revisionHash(JsonNode revision) {
    bounded(revision, MAX_GENERIC_BYTES, "母本版本");
    if (!revision.isObject()) throw invalid("母本版本必须是对象");
    ObjectNode content = revision.deepCopy();
    content.remove("hash");
    return hash(content);
  }

  public static ObjectNode validatePack(JsonNode input) {
    bounded(input, MAX_PACK_BYTES, "母本资料包");
    exact(input, keys("format", "schemaVersion", "exportedAt", "templates", "revisions"), "母本资料包");
    if (!"novel-assistant-series-v1".equals(input.path("format").asText()))
      throw invalid("母本资料包格式无效");
    schema(input);
    ObjectNode result = JSON.createObjectNode().put("format", "novel-assistant-series-v1")
      .put("schemaVersion", 1).put("exportedAt", timestamp(input, "exportedAt"));
    ArrayNode templates = result.putArray("templates");
    Map<String, ObjectNode> byTemplate = new LinkedHashMap<>();
    for (JsonNode row : array(input, "templates")) {
      exact(row, keys("uid", "kind", "headRevisionUid", "archived"), "母本资料包目录");
      ObjectNode header = JSON.createObjectNode().put("uid", uid(row, "uid")).put("kind", kind(row))
        .put("headRevisionUid", uid(row, "headRevisionUid")).put("archived", bool(row, "archived"));
      if (byTemplate.putIfAbsent(header.path("uid").asText(), header) != null)
        throw invalid("母本资料包包含重复母本标识");
      templates.add(header);
    }
    Map<String, ObjectNode> revisions = revisionIndex(array(input, "revisions"), result.putArray("revisions"));
    validateGraph(revisions);
    for (ObjectNode revision : revisions.values()) {
      ObjectNode template = byTemplate.get(revision.path("templateUid").asText());
      if (template == null || !template.path("kind").equals(revision.path("kind")))
        throw invalid("母本版本的所属母本或类型不匹配");
    }
    for (ObjectNode template : byTemplate.values()) {
      requireRevision(revisions, template.path("headRevisionUid").asText(),
        template.path("uid").asText(), template.path("kind").asText());
    }
    bounded(result, MAX_PACK_BYTES, "母本资料包");
    return result;
  }

  public static ObjectNode validateState(JsonNode input, boolean requireEpoch) {
    bounded(input, MAX_STATE_BYTES, "本作系列设定");
    Set<String> stateFields = new HashSet<>(keys("schemaVersion", "version", "worlds", "copies", "sourceRevisions"));
    if (requireEpoch) stateFields.add("epoch");
    exact(input, stateFields, "本作系列设定");
    schema(input);
    ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("version", counter(input, "version"));
    if (requireEpoch) result.put("epoch", uid(input, "epoch"));
    ArrayNode worlds = array(input, "worlds");
    if (worlds.isEmpty()) throw invalid("本作至少需要一个世界");
    if (worlds.size() > 100) throw capacity("本作世界超过 100 个上限");
    Set<String> worldIds = new HashSet<>();
    ArrayNode savedWorlds = result.putArray("worlds");
    for (JsonNode world : worlds) {
      exact(world, keys("uid", "name", "description"), "本作世界");
      String id = uid(world, "uid");
      if (!worldIds.add(id)) throw invalid("本作世界标识重复");
      savedWorlds.addObject().put("uid", id).put("name", text(world, "name", 100, true))
        .put("description", text(world, "description", 20_000, false));
    }
    Map<String, ObjectNode> revisions = revisionIndex(array(input, "sourceRevisions"), result.putArray("sourceRevisions"));
    validateGraph(revisions);
    ArrayNode copies = array(input, "copies");
    if (copies.size() > 1_000) throw capacity("本作设定副本超过 1000 个上限");
    Set<String> copyIds = new HashSet<>(), pinned = new HashSet<>();
    ArrayNode savedCopies = result.putArray("copies");
    for (JsonNode copy : copies) {
      exact(copy, COPY_FIELDS, "本作设定副本");
      String id = uid(copy, "uid"), kind = kind(copy);
      if (!copyIds.add(id)) throw invalid("本作设定副本标识重复");
      JsonNode origin = copy.get("origin");
      exact(origin, keys("templateUid", "revisionUid", "copiedAt"), "副本初始来源");
      String template = uid(origin, "templateUid"), initial = uid(origin, "revisionUid");
      pin(revisions, pinned, initial, template, kind);
      ObjectNode saved = snapshot(copy, kind, template, worldIds, revisions, pinned, false);
      saved.put("uid", id).put("kind", kind).put("createdAt", timestamp(copy, "createdAt"))
        .put("updatedAt", timestamp(copy, "updatedAt"));
      saved.putObject("origin").put("templateUid", template).put("revisionUid", initial)
        .put("copiedAt", timestamp(origin, "copiedAt"));
      ArrayNode history = saved.putArray("history");
      Set<String> historyIds = new HashSet<>();
      for (JsonNode entry : array(copy, "history")) {
        exact(entry, keys("uid", "createdAt", "action", "before"), "副本历史");
        String historyId = uid(entry, "uid");
        if (!historyIds.add(historyId)) throw invalid("副本历史标识重复");
        String action = choice(entry, "action", keys("edit", "adopt", "review", "restore", "archive"));
        ObjectNode before = snapshot(entry.get("before"), kind, template, worldIds, revisions, pinned, true);
        if (!before.path("universeUid").equals(saved.path("universeUid")))
          throw invalid("副本历史不能指向其他平行世界");
        ObjectNode historical = history.addObject().put("uid", historyId)
          .put("createdAt", timestamp(entry, "createdAt")).put("action", action);
        historical.set("before", before);
      }
      savedCopies.add(saved);
    }
    Set<String> used = closure(pinned, revisions);
    if (!used.equals(revisions.keySet())) throw invalid("本作来源闭包包含未引用的母本版本");
    bounded(result, MAX_STATE_BYTES, "本作系列设定");
    return result;
  }

  public static ObjectNode validateBackup(JsonNode input) {
    bounded(input, MAX_STATE_BYTES + MAX_PACK_BYTES + 1024, "系列设定备份");
    exact(input, keys("schemaVersion", "document", "library"), "系列设定备份");
    schema(input);
    ObjectNode document = validateState(input.get("document"), false);
    ObjectNode library = validatePack(input.get("library"));
    Map<String, JsonNode> included = new HashMap<>();
    for (JsonNode revision : library.path("revisions")) included.put(revision.path("uid").asText(), revision);
    Set<String> sources = new HashSet<>();
    for (JsonNode revision : document.path("sourceRevisions")) {
      String id = revision.path("uid").asText();
      sources.add(id);
      if (!revision.equals(included.get(id))) throw invalid("系列备份的文档来源与母本资料包不一致");
    }
    if (!sources.equals(included.keySet())) throw invalid("作品备份不能包含未引用的母本版本");
    Set<String> allowedHeads = new HashSet<>();
    for (JsonNode copy : document.path("copies")) {
      allowedHeads.add(copy.path("origin").path("revisionUid").asText());
      allowedHeads.add(copy.path("baselineRevisionUid").asText());
      for (JsonNode history : copy.path("history")) allowedHeads.add(history.path("before").path("baselineRevisionUid").asText());
    }
    for (JsonNode template : library.path("templates"))
      if (!allowedHeads.contains(template.path("headRevisionUid").asText()))
        throw invalid("作品备份的母本默认版本必须来自已采用或已比较的来源");
    ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1);
    result.set("document", document);
    result.set("library", library);
    return result;
  }

  /** Conservative guard for old backups; malformed persisted state is never considered empty. */
  public static boolean hasData(JsonNode state) {
    if (state == null || state.isNull() || state.isMissingNode()) return false;
    if (!state.isObject() || !state.path("copies").isArray() || !state.path("sourceRevisions").isArray()
      || !state.path("copies").isEmpty() || !state.path("sourceRevisions").isEmpty()) return true;
    try { validateState(state, state.has("epoch")); }
    catch (SeriesProblem invalid) { return true; }
    JsonNode worlds = state.path("worlds");
    return !worlds.isArray() || worlds.size() != 1 || !"本作世界".equals(worlds.get(0).path("name").asText())
      || !worlds.get(0).path("description").isTextual() || !worlds.get(0).path("description").asText().isEmpty();
  }

  public static String hash(JsonNode node) {
    bounded(node, MAX_GENERIC_BYTES, "系列数据");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(JSON.writeValueAsBytes(canonical(node))));
    } catch (Exception e) {
      throw invalid("系列数据无法计算校验值");
    }
  }

  public static String stringify(JsonNode node) {
    bounded(node, MAX_GENERIC_BYTES, "系列数据");
    try { return JSON.writeValueAsString(node); }
    catch (Exception e) { throw invalid("系列数据无法序列化"); }
  }

  private static ObjectNode snapshot(JsonNode input, String kind, String template, Set<String> worldIds,
    Map<String, ObjectNode> revisions, Set<String> pinned, boolean exactSnapshot) {
    if (exactSnapshot) exact(input, SNAPSHOT_FIELDS, "副本历史快照");
    String universe = uid(input, "universeUid");
    if (!worldIds.contains(universe)) throw invalid("副本所属世界不存在");
    String baseline = uid(input, "baselineRevisionUid");
    pin(revisions, pinned, baseline, template, kind);
    ObjectNode result = JSON.createObjectNode().put("universeUid", universe)
      .put("planet", text(input, "planet", 200, false)).put("authorStatus", status(input))
      .put("baselineRevisionUid", baseline).put("archived", bool(input, "archived"));
    ObjectNode content = validatePayload(kind, input.get("content"));
    result.set("content", content);
    JsonNode review = input.get("lastReview");
    exact(review, keys("revisionUid", "adoptedFields", "keptFields", "reviewedAt", "mode"), "来源比较记录");
    String reviewed = uid(review, "revisionUid");
    if (!baseline.equals(reviewed)) throw invalid("比较记录与完整来源基线不一致");
    pin(revisions, pinned, reviewed, template, kind);
    String mode = choice(review, "mode", keys("copy", "adopt", "review"));
    Set<String> seen = new HashSet<>();
    ArrayNode adopted = fieldList(review, "adoptedFields", kind, seen);
    ArrayNode kept = fieldList(review, "keptFields", kind, seen);
    if (!seen.equals(new HashSet<>(fields(kind)))) throw invalid("比较记录必须列出全部采用和保留字段");
    if ((mode.equals("review") && !adopted.isEmpty()) || (mode.equals("copy") && !kept.isEmpty())
      || (mode.equals("adopt") && adopted.isEmpty())) throw invalid("比较记录的操作与字段选择不一致");
    ObjectNode savedReview = result.putObject("lastReview").put("revisionUid", reviewed)
      .put("mode", mode).put("reviewedAt", timestamp(review, "reviewedAt"));
    savedReview.set("adoptedFields", adopted);
    savedReview.set("keptFields", kept);
    JsonNode origins = input.get("fieldOrigins");
    exact(origins, new HashSet<>(fields(kind)), "字段来源");
    ObjectNode savedOrigins = result.putObject("fieldOrigins");
    for (String key : fields(kind)) {
      JsonNode origin = origins.get(key);
      exact(origin, keys("kind", "revisionUid"), "字段来源");
      String originKind = choice(origin, "kind", keys("source", "local"));
      ObjectNode savedOrigin = savedOrigins.putObject(key).put("kind", originKind);
      if (originKind.equals("local")) {
        if (!origin.get("revisionUid").isNull()) throw invalid("本地字段不能声明母本版本来源");
        savedOrigin.putNull("revisionUid");
      } else {
        String source = uid(origin, "revisionUid");
        pin(revisions, pinned, source, template, kind);
        if (!content.path(key).equals(revisions.get(source).path("payload").path(key)))
          throw invalid("字段内容与声明的母本来源不一致");
        savedOrigin.put("revisionUid", source);
      }
    }
    return result;
  }

  private static ArrayNode fieldList(JsonNode node, String field, String kind, Set<String> seen) {
    ArrayNode result = JSON.createArrayNode();
    for (JsonNode key : array(node, field)) {
      if (!key.isTextual() || !fields(kind).contains(key.asText()) || !seen.add(key.asText()))
        throw invalid("比较字段无效或重复");
      result.add(key.asText());
    }
    return result;
  }

  private static Map<String, ObjectNode> revisionIndex(ArrayNode rows, ArrayNode result) {
    Map<String, ObjectNode> revisions = new LinkedHashMap<>();
    for (JsonNode row : rows) {
      ObjectNode revision = validateRevision(row);
      if (revisions.putIfAbsent(revision.path("uid").asText(), revision) != null)
        throw invalid("母本版本标识重复");
      result.add(revision);
    }
    return revisions;
  }

  /** Iterative traversal: even a long untrusted chain cannot exhaust the Java stack. */
  private static void validateGraph(Map<String, ObjectNode> revisions) {
    Map<String, String> templateKinds = new HashMap<>();
    for (ObjectNode revision : revisions.values()) {
      String template = revision.path("templateUid").asText(), kind = revision.path("kind").asText();
      String prior = templateKinds.putIfAbsent(template, kind);
      if (prior != null && !prior.equals(kind)) throw invalid("同一母本的版本类型必须一致");
      if (!revision.path("parentRevisionUid").isNull())
        requireRevision(revisions, revision.path("parentRevisionUid").asText(), template, kind);
    }
    Set<String> checked = new HashSet<>();
    for (String start : revisions.keySet()) {
      Set<String> path = new HashSet<>();
      String current = start;
      while (current != null && !checked.contains(current)) {
        if (!path.add(current)) throw invalid("母本版本父链存在循环");
        JsonNode parent = revisions.get(current).path("parentRevisionUid");
        current = parent.isNull() ? null : parent.asText();
      }
      checked.addAll(path);
    }
  }

  private static Set<String> closure(Set<String> roots, Map<String, ObjectNode> revisions) {
    Set<String> result = new HashSet<>();
    for (String root : roots) {
      String current = root;
      while (current != null && result.add(current)) {
        JsonNode parent = revisions.get(current).path("parentRevisionUid");
        current = parent.isNull() ? null : parent.asText();
      }
    }
    return result;
  }

  private static void pin(Map<String, ObjectNode> revisions, Set<String> pinned, String id, String template, String kind) {
    requireRevision(revisions, id, template, kind);
    pinned.add(id);
  }

  private static void requireRevision(Map<String, ObjectNode> revisions, String id, String template, String kind) {
    JsonNode revision = revisions.get(id);
    if (revision == null) throw invalid("来源版本或其祖先不在完整闭包中");
    if (!template.equals(revision.path("templateUid").asText()) || !kind.equals(revision.path("kind").asText()))
      throw invalid("来源版本指向了其他母本或类型");
  }

  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) result.set(name, canonical(node.get(name)));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      for (JsonNode child : node) result.add(canonical(child));
      return result;
    }
    return node.deepCopy();
  }

  private record Walk(JsonNode node, int depth) {}
  private static void bounded(JsonNode input, int max, String label) {
    if (input == null || input.isMissingNode()) throw invalid(label + "缺失");
    ArrayDeque<Walk> pending = new ArrayDeque<>();
    pending.push(new Walk(input, 0));
    long lowerBound = 0;
    while (!pending.isEmpty()) {
      Walk entry = pending.pop();
      if (entry.depth() > MAX_DEPTH) throw invalid(label + "嵌套过深");
      JsonNode node = entry.node();
      lowerBound += 1;
      if (node.isObject()) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
          var field = it.next();
          lowerBound += field.getKey().getBytes(StandardCharsets.UTF_8).length;
          pending.push(new Walk(field.getValue(), entry.depth() + 1));
        }
      } else if (node.isArray()) {
        for (JsonNode child : node) pending.push(new Walk(child, entry.depth() + 1));
      } else if (node.isTextual()) lowerBound += node.textValue().getBytes(StandardCharsets.UTF_8).length;
      else if (!(node.isNull() || node.isBoolean() || node.isNumber())) throw invalid(label + "包含不支持的数据类型");
      if (lowerBound > max || pending.size() > max) throw capacity(label + "超过容量上限");
    }
    try {
      if (JSON.writeValueAsBytes(input).length > max) throw capacity(label + "超过容量上限");
    } catch (SeriesProblem e) { throw e; }
    catch (Exception e) { throw invalid(label + "无法序列化"); }
  }

  private static Set<String> keys(String... names) { return Set.of(names); }
  private static void exact(JsonNode node, Set<String> fields, String label) {
    if (node == null || !node.isObject()) throw invalid(label + "必须是对象");
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(fields)) throw invalid(label + "存在缺失或不支持的字段");
  }
  private static ArrayNode array(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isArray()) throw invalid(key + "必须是数组");
    return (ArrayNode) value;
  }
  private static String text(JsonNode node, String key, int max, boolean name) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual()) throw invalid(key + "必须是文字");
    String result = name ? value.asText().strip() : value.asText();
    if (result.length() > max) throw capacity(key + "超过 " + max + " 字符上限");
    if (name && result.isBlank()) throw invalid(key + "不能为空");
    return result;
  }
  private static String uid(JsonNode node, String key) {
    String value = text(node, key, 36, false);
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException e) { throw invalid(key + "必须是规范的小写 UUID"); }
  }
  private static String timestamp(JsonNode node, String key) {
    String value = text(node, key, 40, false);
    try {
      if (!value.endsWith("Z")) throw new IllegalArgumentException();
      return Instant.parse(value).toString();
    } catch (Exception e) { throw invalid(key + "必须是 UTC 时间"); }
  }
  private static String choice(JsonNode node, String key, Set<String> choices) {
    String value = text(node, key, 50, false);
    if (!choices.contains(value)) throw invalid(key + "选项无效");
    return value;
  }
  private static String kind(JsonNode node) { return choice(node, "kind", FIELDS.keySet()); }
  private static String status(JsonNode node) { return choice(node, "authorStatus", keys("draft", "confirmed")); }
  private static long counter(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
      || value.asLong() < 0 || value.asLong() > MAX_COUNTER) throw invalid(key + "必须是安全范围内的非负整数");
    return value.asLong();
  }
  private static boolean bool(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isBoolean()) throw invalid(key + "必须是布尔值");
    return value.booleanValue();
  }
  private static void schema(JsonNode node) {
    if (counter(node, "schemaVersion") != 1) throw invalid("不支持的系列设定版本");
  }
  private static SeriesProblem invalid(String message) { return new SeriesProblem(400, "invalid_series", message); }
  private static SeriesProblem capacity(String message) { return new SeriesProblem(413, "series_capacity", message); }
}
