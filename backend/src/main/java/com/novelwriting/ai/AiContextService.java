package com.novelwriting.ai;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.*;
import com.novelwriting.service.WritingDeskDocuments;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Captures a bounded immutable read snapshot before any provider call; never saves a draft. */
@Service
public class AiContextService {
  @PersistenceContext private EntityManager em;
  public static final int SELECTED_MANUSCRIPT_LIMIT = 10000;

  record ResourceSpec(Class<?> type, List<String> fields, List<String> references) {}
  private static ResourceSpec spec(Class<?> type, String fields, String refs) {
    return new ResourceSpec(type, List.of(fields.split(",")), refs.isEmpty() ? List.of() : List.of(refs.split(",")));
  }
  static final Map<String, ResourceSpec> RESOURCES;
  static {
    Map<String, ResourceSpec> rows = new LinkedHashMap<>();
    rows.put("characters", spec(com.novelwriting.entity.Character.class, "name,description,personality,appearance,background,role", "tags"));
    rows.put("map", spec(MapLocation.class, "name,description,locationType,positionX,positionY", "parentLocation,tags"));
    rows.put("scenes", spec(Scene.class, "name,description,location,atmosphere", "mapLocations,tags"));
    rows.put("worldview", spec(WorldviewEntry.class, "name,category,content", "characters,scenes,mapLocations,tags"));
    rows.put("outlines", spec(Outline.class, "title,content,chapterNumber,plotOrder,status", "tags"));
    rows.put("timeline", spec(TimelineEvent.class, "title,description,eventTime,realOrder", "characters,scenes,foreshadows,outlines,tags"));
    rows.put("foreshadows", spec(Foreshadow.class, "title,content,laidAt,revealedAt,status", "tags"));
    rows.put("relationships", spec(CharacterRelationship.class, "characterId1,characterId2,relationshipType,description", ""));
    rows.put("relationshipGroups", spec(RelationshipGroup.class, "name,description,parentGroupId", "characters"));
    rows.put("tags", spec(Tag.class, "name,description", ""));
    RESOURCES = Collections.unmodifiableMap(rows);
  }

  public record Snapshot(ObjectNode data, ObjectNode coverage, ArrayNode sources, Set<String> sourceIds, String hash) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot capture(long novelId, AiActions.Action action, JsonNode input, int maxChars) {
    if (input == null || !input.isObject()) throw AiActions.invalid("AI 上下文格式无效");
    Set<String> allowed = Set.of("targetId", "targetField", "targetDraft", "chapterUid", "currentDraft", "request", "referenceChapterUids");
    input.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw AiActions.invalid("AI 上下文包含不支持的字段"); });
    if (input.toString().length() > 2_200_000) throw capacity();
    Novel novel = em.find(Novel.class, novelId);
    if (novel == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
    ObjectNode data = JSON.createObjectNode().put("novelId", novelId).put("capturedAt", Instant.now().toString())
      .put("taskMode", action.mode()).put("targetResource", action.resource()).put("targetField", action.field());
    ObjectNode coverage = JSON.createObjectNode().put("mode", action.writing() ? "full" : "settings")
      .put("complete", true).put("includesUnsavedDraft", false).put("chaptersBefore", 0)
      .put("currentChapter", 0).put("chaptersAfter", 0).put("progress", action.writing() ? "chapter-end" : "selected-reference")
      .put("selectedChapterCount", 0).put("selectedManuscriptChars", 0);
    ArrayNode sources = JSON.createArrayNode();
    Set<String> ids = new LinkedHashSet<>();
    ObjectNode about = JSON.createObjectNode().put("title", novel.getTitle())
      .put("description", novel.getDescription()).put("genre", novel.getGenre());
    add(data.putArray("novel"), sources, ids, "novel:" + novelId, "novel", novel.getTitle(), about, null);
    ArrayNode settings = data.putArray("settings");
    ObjectNode target = null;
    Long targetId = null;
    if (input.hasNonNull("targetId")) {
      if (!input.path("targetId").canConvertToLong() || !input.path("targetId").isIntegralNumber() || input.path("targetId").asLong() <= 0)
        throw AiActions.invalid("目标资料标识无效");
      targetId = input.path("targetId").asLong();
    }
    if (action.writing() && input.has("referenceChapterUids")) throw AiActions.invalid("写作工作台使用完整正文范围，不接受设定页的所选章节参数");
    if (action.writing() && (targetId != null || input.has("targetDraft") || input.has("targetField")))
      throw AiActions.invalid("写作灵感不接受资料修改目标");
    for (var entry : RESOURCES.entrySet()) {
      ResourceSpec type = entry.getValue();
      for (Object entity : em.createQuery("from " + type.type().getSimpleName() + " where novelId=:id order by id", type.type())
          .setParameter("id", novelId).getResultList()) {
        ObjectNode content = resource(entity, type);
        long id = content.path("id").asLong();
        String source = entry.getKey() + ":" + id;
        String title = content.path("name").asText(content.path("title").asText(entry.getKey() + " " + id));
        add(settings, sources, ids, source, entry.getKey(), title, content, null);
        if (entry.getKey().equals(action.resource()) && Objects.equals(targetId, id)) target = content.deepCopy();
      }
      bound(data, maxChars);
    }
    if (targetId != null && target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标资料不属于当前作品");
    if (!action.writing()) {
      if (input.has("chapterUid") || input.has("currentDraft")) throw AiActions.invalid("设定功能只能通过明确勾选的章节引用已保存正文，不接受当前稿件或正文覆盖");
      if (input.hasNonNull("targetField") && !input.path("targetField").asText().equals(action.field()))
        throw AiActions.invalid("目标字段与 AI 功能不一致");
      if (target == null) target = JSON.createObjectNode();
      mergeDraft(target, input.get("targetDraft"), RESOURCES.get(action.resource()));
      validateDraftReferences(target, input.get("targetDraft"), action.resource(), ids);
      add(data.putArray("target"), sources, ids, "target-draft", "draft", "当前表单（尚未采用）", target, null);
    }
    WritingBook book = em.find(WritingBook.class, novelId);
    ObjectNode desk = WritingDeskDocuments.read(book);
    if (book != null) data.put("bookUid", book.getUid()).put("structureVersion", book.getStructureVersion());
    data.put("deskVersion", desk.path("version").asLong());
    if (book != null) {
      JsonNode aliases = parse(book.getPreferences()).path("aliases");
      if (aliases.isObject() && !aliases.isEmpty()) {
        ObjectNode ownedAliases = JSON.createObjectNode();
        aliases.fields().forEachRemaining(entry -> {
          if (ids.contains("characters:" + entry.getKey()) && entry.getValue().isArray())
            ownedAliases.set(entry.getKey(), entry.getValue());
        });
        add(settings, sources, ids, "character-aliases", "aliases", "本作人物别名", ownedAliases, null);
      }
    }
    if (desk.path("storyState").isObject()) {
      JsonNode storyState = desk.path("storyState").deepCopy();
      // Settings requests retain explicit author records and source anchors, but do not
      // forward manuscript quotations copied into nested state/knowledge/reader sources.
      if (!action.writing()) removeExcerpts(storyState);
      add(settings, sources, ids, "story-state", "storyState", "作者记录的状态与知情历史（不等于当前已知）", storyState, desk.path("version").asLong());
    }
    if (book != null && book.getSeriesData() != null) {
      JsonNode series = parse(book.getSeriesData());
      ObjectNode adopted = JSON.createObjectNode();
      adopted.set("worlds", series.path("worlds"));
      ArrayNode copies = adopted.putArray("copies");
      for (JsonNode copy : series.path("copies")) if (!copy.path("archived").asBoolean()) {
        ObjectNode kept = JSON.createObjectNode();
        for (String key : List.of("uid", "kind", "universeUid", "planet", "authorStatus", "content", "fieldOrigins", "updatedAt"))
          if (copy.has(key)) kept.set(key, copy.get(key));
        copies.add(kept);
      }
      add(settings, sources, ids, "series-current", "series", "本作系列副本（各世界范围不得混用）", adopted, series.path("version").asLong());
    }
    if (action.writing()) manuscript(novelId, input, book, desk, data, coverage, sources, ids, maxChars);
    else selectedManuscript(novelId, input, book, data, coverage, sources, ids, maxChars);
    String request = boundedText(input.path("request"), 1500, false);
    data.put("authorRequest", request);
    coverage.put("settingsCount", settings.size()).put("sourceCount", sources.size());
    coverage.putArray("excluded").add("回收站章节").add("历史正文版本").add("未采用的母本版本").add("其他作品").add("图片、凭证与同步配置");
    coverage.putArray("limitations").add(action.writing() ? "按当前章节末尾推演；没有自动推断人物或读者知情"
      : "所选正文仅为参考资料，不指定当前推进时点，也不自动推断人物或读者当前知情")
      .add("本次完整发送列明的文字资料；不代表模型理解无遗漏");
    if (!action.writing()) {
      coverage.withArray("excluded").add("未勾选的章节正文").add("未保存正文与创作便笺").add("状态与知情账来源中的正文摘录");
      coverage.withArray("limitations").add("只发送本次明确勾选的已保存正文；未发送其他章节，不代表全文核对")
        .add("所选正文合计最多10000字，计数与编辑器一致：中日韩文字逐字，英文按词");
    }
    bound(data, maxChars);
    ObjectNode comparable = data.deepCopy();
    comparable.remove("capturedAt");
    ObjectNode identity = JSON.createObjectNode();
    identity.set("content", comparable);
    identity.set("sources", sources);
    return new Snapshot(data, coverage, sources, ids, hash(identity));
  }

  /** Metadata only: listing references never loads chapter documents or contacts a provider. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ObjectNode referenceChapters(long novelId) {
    if (novelId <= 0) throw AiActions.invalid("作品编号无效");
    if (em.find(Novel.class, novelId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在");
    List<Object[]> rows = new ArrayList<>(em.createQuery(
      "select uid,title,wordCount,volumeId,position,id from WritingChapter where novelId=:id and deleted=false order by position,id", Object[].class)
      .setParameter("id", novelId).getResultList());
    List<String> volumes = em.createQuery("select volumes from WritingBook where novelId=:id", String.class).setParameter("id", novelId).getResultList();
    Map<String, Integer> order = volumeOrder(volumes.isEmpty() ? null : volumes.get(0));
    rows.sort(Comparator.comparingInt((Object[] row) -> row[3] == null ? -1 : order.getOrDefault(row[3].toString(), Integer.MAX_VALUE))
      .thenComparingInt(row -> ((Number) row[4]).intValue()).thenComparingLong(row -> ((Number) row[5]).longValue()));
    ObjectNode result = JSON.createObjectNode().put("maxChars", SELECTED_MANUSCRIPT_LIMIT);
    ArrayNode chapters = result.putArray("chapters");
    for (Object[] row : rows) chapters.addObject().put("uid", row[0].toString()).put("title", Objects.toString(row[1], ""))
      .put("wordCount", ((Number) row[2]).intValue());
    return result;
  }

  private void selectedManuscript(long novelId, JsonNode input, WritingBook book, ObjectNode data, ObjectNode coverage,
      ArrayNode sources, Set<String> ids, int maxChars) {
    ArrayNode selected = data.putArray("selectedManuscript");
    JsonNode selection = input.get("referenceChapterUids");
    if (selection == null) return;
    if (!selection.isArray() || selection.size() > 1000) throw AiActions.invalid("参考正文必须是本作章节编号列表，最多1000项");
    Set<String> requested = new LinkedHashSet<>();
    for (JsonNode value : selection) {
      if (!value.isTextual() || !value.asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        || !requested.add(value.asText())) throw AiActions.invalid("参考正文章节编号无效或重复，请重新勾选");
    }
    if (requested.isEmpty()) return;
    // The query is constrained before document loading: no unselected or other-novel body is read.
    List<WritingChapter> chapters = new ArrayList<>(em.createQuery(
      "from WritingChapter where novelId=:id and deleted=false and uid in :uids order by position,id", WritingChapter.class)
      .setParameter("id", novelId).setParameter("uids", requested).getResultList());
    if (chapters.size() != requested.size())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所选正文章节不存在、已移入回收站或不属于当前作品；没有发送任何正文，请重新勾选");
    Map<String, Integer> order = volumeOrder(book == null ? null : book.getVolumes());
    chapters.sort(Comparator.comparingInt((WritingChapter row) -> row.getVolumeId() == null ? -1 : order.getOrDefault(row.getVolumeId(), Integer.MAX_VALUE))
      .thenComparingInt(WritingChapter::getPosition).thenComparing(WritingChapter::getId));
    long total = 0;
    for (WritingChapter chapter : chapters) {
      JsonNode doc = parse(chapter.getDocument());
      int words = count(text(doc));
      total += words;
      if (total > SELECTED_MANUSCRIPT_LIMIT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
        "所选正文合计超过10000字上限（与编辑器计数相同）；没有发送或截断正文，请减少勾选章节");
      ObjectNode content = JSON.createObjectNode().put("chapterUid", chapter.getUid()).put("title", chapter.getTitle())
        .put("wordCount", words).put("savedOnly", true);
      blockText(doc, content.putArray("blocks"));
      add(selected, sources, ids, "chapter:" + chapter.getUid(), "chapter", chapter.getTitle(), content, chapter.getRevision());
      bound(data, maxChars);
    }
    coverage.put("selectedChapterCount", chapters.size()).put("selectedManuscriptChars", total);
  }

  private static Map<String, Integer> volumeOrder(String volumes) {
    Map<String, Integer> order = new HashMap<>();
    if (volumes != null) for (JsonNode volume : parse(volumes)) order.put(volume.path("uid").asText(), order.size());
    return order;
  }

  private void manuscript(long novelId, JsonNode input, WritingBook book, ObjectNode desk, ObjectNode data,
      ObjectNode coverage, ArrayNode sources, Set<String> ids, int maxChars) {
    String chapterUid = boundedText(input.path("chapterUid"), 80, true);
    List<WritingChapter> chapters = new ArrayList<>(em.createQuery("from WritingChapter where novelId=:id and deleted=false order by position,id", WritingChapter.class)
      .setParameter("id", novelId).getResultList());
    Map<String, Integer> volumeOrder = new HashMap<>();
    if (book != null) for (JsonNode volume : parse(book.getVolumes())) volumeOrder.put(volume.path("uid").asText(), volumeOrder.size());
    chapters.sort(Comparator.comparingInt((WritingChapter row) -> row.getVolumeId() == null ? -1 : volumeOrder.getOrDefault(row.getVolumeId(), Integer.MAX_VALUE))
      .thenComparingInt(WritingChapter::getPosition).thenComparing(WritingChapter::getId));
    if (chapters.stream().noneMatch(row -> row.getUid().equals(chapterUid)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前章节不存在或已移入回收站");
    ArrayNode manuscript = data.putArray("manuscript");
    boolean after = false;
    for (WritingChapter chapter : chapters) {
      boolean current = chapterUid.equals(chapter.getUid());
      String phase = current ? "current" : after ? "after" : "before";
      ObjectNode row = JSON.createObjectNode().put("chapterUid", chapter.getUid()).put("title", chapter.getTitle())
        .put("phase", phase).put("status", chapter.getStatus()).put("chapterGoal", chapter.getSummary()).put("notes", chapter.getNotes());
      JsonNode doc = parse(chapter.getDocument());
      JsonNode links = parse(chapter.getLinks());
      if (current && input.hasNonNull("currentDraft")) {
        JsonNode draft = input.get("currentDraft");
        if (!draft.isObject() || !chapterUid.equals(draft.path("uid").asText()) || !draft.path("revision").isIntegralNumber()
            || draft.path("revision").asLong() != chapter.getRevision())
          throw new ResponseStatusException(HttpStatus.CONFLICT, "稿件版本刚发生变化，请重新获取灵感；如有版本冲突，请先比较。当前草稿未修改。");
        try { doc = validate(draft.get("doc")); }
        catch (IllegalArgumentException error) { throw AiActions.invalid("当前草稿正文格式无效"); }
        row.put("title", boundedText(draft.path("title"), 200, true));
        row.put("chapterGoal", boundedText(draft.path("summary"), 10000, false));
        row.put("notes", boundedText(draft.path("notes"), 50000, false));
        links = safeLinks(draft.path("links"), ids);
        coverage.put("includesUnsavedDraft", true).put("draftHash", hash(draft));
        row.put("isLocalSnapshot", true);
      }
      row.set("links", links);
      ArrayNode blocks = row.putArray("blocks");
      blockText(doc, blocks);
      add(manuscript, sources, ids, "chapter:" + chapter.getUid(), "chapter", chapter.getTitle(), row, chapter.getRevision());
      String count = current ? "currentChapter" : after ? "chaptersAfter" : "chaptersBefore";
      coverage.put(count, coverage.path(count).asInt() + 1);
      if (current) after = true;
      bound(data, maxChars);
    }
    data.put("progressChapterUid", chapterUid);
    ObjectNode plans = JSON.createObjectNode();
    for (String key : List.of("nextPen", "tasks", "ideas")) if (desk.has(key)) plans.set(key, desk.get(key));
    add(data.putArray("authorPlans"), sources, ids, "creative-desk", "plans", "作者便笺与候选灵感（尚未发生）", plans, desk.path("version").asLong());
  }

  private static ObjectNode resource(Object entity, ResourceSpec spec) {
    BeanWrapperImpl bean = new BeanWrapperImpl(entity);
    ObjectNode row = JSON.createObjectNode().put("id", ((Number) bean.getPropertyValue("id")).longValue());
    for (String field : spec.fields()) row.set(field, JSON.valueToTree(bean.getPropertyValue(field)));
    for (String field : spec.references()) {
      Object ref = bean.getPropertyValue(field);
      if (ref instanceof Collection<?> collection) {
        List<Long> values = new ArrayList<>();
        for (Object item : collection) values.add(((Number) new BeanWrapperImpl(item).getPropertyValue("id")).longValue());
        Collections.sort(values);
        row.set(field, JSON.valueToTree(values));
      } else row.set(field, ref == null ? JSON.nullNode() : JSON.valueToTree(new BeanWrapperImpl(ref).getPropertyValue("id")));
    }
    return row;
  }

  static void mergeDraft(ObjectNode target, JsonNode draft, ResourceSpec spec) {
    if (draft == null || draft.isNull()) return;
    if (!draft.isObject()) throw AiActions.invalid("当前表单草稿格式无效");
    Set<String> fields = new HashSet<>(spec.fields());
    // Only allowlisted scalar fields enter directly. Form association IDs are
    // handled separately and must resolve to an existing resource in this novel.
    // Images and unknown fields from a complete form never enter the prompt.
    draft.fields().forEachRemaining(entry -> {
      if (!fields.contains(entry.getKey())) return;
      JsonNode value = entry.getValue();
      if (!value.isNull() && !value.isTextual() && !value.isNumber() && !value.isBoolean()) throw AiActions.invalid("表单字段必须是文字或数值");
      if (value.isTextual() && value.asText().length() > 20000) throw capacity();
      target.set(entry.getKey(), value.deepCopy());
    });
  }

  private static void validateDraftReferences(ObjectNode target, JsonNode draft, String resource, Set<String> sources) {
    if (draft != null && draft.isObject()) {
      Map<String, String> inputFields = Map.of("tags", "tagIds", "characters", "characterIds", "scenes", "sceneIds",
        "mapLocations", "mapLocationIds", "foreshadows", "foreshadowIds", "outlines", "outlineIds");
      for (String reference : RESOURCES.get(resource).references()) {
        String inputField = inputFields.get(reference);
        if (inputField == null || !draft.has(inputField)) continue;
        JsonNode values = draft.get(inputField);
        if (!values.isArray() || values.size() > 1000) throw AiActions.invalid("当前表单关联必须是资料编号列表");
        String type = reference.equals("mapLocations") ? "map" : reference;
        Set<Long> ids = new TreeSet<>();
        for (JsonNode value : values) ids.add(referenceId(value, type, sources));
        target.set(reference, JSON.valueToTree(ids));
      }
    }
    if (resource.equals("relationships")) {
      for (String field : List.of("characterId1", "characterId2")) {
        JsonNode value = target.get(field);
        if (value != null && !value.isNull() && !value.asText().isBlank()) {
          long id = referenceId(value, "characters", sources);
          target.put(field, id);
        }
      }
    }
    if (resource.equals("map") && draft != null && draft.has("parentLocationId")) {
      JsonNode value = draft.get("parentLocationId");
      if (value.isNull() || value.asText().isBlank()) target.putNull("parentLocation");
      else target.put("parentLocation", referenceId(value, "map", sources));
    }
  }
  private static long referenceId(JsonNode value, String type, Set<String> sources) {
    String raw = value.asText();
    if (!raw.matches("[1-9][0-9]{0,17}") || !sources.contains(type + ":" + raw))
      throw AiActions.invalid("当前表单包含不可用或跨作品资料关联");
    return Long.parseLong(raw);
  }

  private static JsonNode safeLinks(JsonNode links, Set<String> sourceIds) {
    if (!links.isArray() || links.size() > 1000) throw AiActions.invalid("当前草稿关联格式无效");
    ArrayNode safe = JSON.createArrayNode();
    for (JsonNode link : links) {
      if (!link.isObject()) throw AiActions.invalid("当前草稿关联格式无效");
      if (link.hasNonNull("targetId") && !sourceIds.contains(link.path("type").asText() + ":" + link.path("targetId").asText())) {
        if (!link.path("missing").asBoolean()) throw AiActions.invalid("当前草稿包含不可用或跨作品资料关联");
      }
      ObjectNode copied = JSON.createObjectNode();
      for (String key : List.of("uid", "type", "targetId", "title", "blockId", "excerpt", "role", "plannedRole", "missing"))
        if (link.has(key)) copied.set(key, link.get(key));
      safe.add(copied);
    }
    return safe;
  }

  private static void blockText(JsonNode node, ArrayNode target) {
    if (Set.of("paragraph", "heading", "horizontalRule").contains(node.path("type").asText())) {
      target.addObject().put("blockId", node.path("attrs").path("id").asText()).put("text", text(node));
    } else for (JsonNode child : node.path("content")) blockText(child, target);
  }
  private static void removeExcerpts(JsonNode node) {
    if (node.isObject()) ((ObjectNode) node).remove("excerpt");
    if (node.isContainerNode()) node.forEach(AiContextService::removeExcerpts);
  }
  static String boundedText(JsonNode node, int max, boolean required) {
    if ((node.isMissingNode() || node.isNull()) && !required) return "";
    if (!node.isTextual() || node.asText().length() > max || (required && node.asText().isBlank())) throw AiActions.invalid("AI 请求文字为空、过长或格式无效");
    return node.asText();
  }
  static void add(ArrayNode rows, ArrayNode sources, Set<String> ids, String id, String type, String title, JsonNode content, Long revision) {
    ObjectNode row = rows.addObject().put("sourceRef", id);
    row.set("content", content.deepCopy());
    ObjectNode source = sources.addObject().put("id", id).put("type", type).put("title", title).put("hash", hash(content));
    if (revision != null) source.put("revision", revision);
    ids.add(id);
  }
  static String hash(JsonNode content) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toString().getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private static void bound(JsonNode data, int max) { if (data.toString().length() > max) throw capacity(); }
  static ResponseStatusException capacity() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "本作资料超过当前 AI 输入上限；未发送任何删减或截断版本。请在 AI 设置中选用更大窗口并调整输入预算，或使用仅资料功能。");
  }
}
