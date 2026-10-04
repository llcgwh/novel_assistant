package com.novelwriting.service;

import static com.novelwriting.service.SeriesDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared immutable sources, and independent novel-owned copies. */
@Service
@Transactional
public class SeriesService {
  private static final long SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final int CATALOG_BYTES = 15 * 1024 * 1024;
  @PersistenceContext private EntityManager em;
  @Autowired private WritingService writing;
  @Autowired private DataSource dataSource;

  // Bootstrap outside request transactions: a failed concurrent INSERT must not
  // abort the author's surrounding transaction. Existing values are never reset.
  @PostConstruct
  public void bootstrapCatalog() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      try (PreparedStatement statement = connection.prepareStatement(
        "insert into series_catalog_state(id, version) values (1, 0)")) {
        statement.executeUpdate();
      } catch (SQLException duplicate) {
        if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot initialize series catalog lock", failure);
    }
  }

  private SeriesCatalogState catalog() {
    SeriesCatalogState root = em.find(SeriesCatalogState.class, 1L, LockModeType.PESSIMISTIC_WRITE);
    if (root == null) throw new IllegalStateException("Missing series catalog lock");
    // Keep earlier changes when a recovery-copy workflow reuses this transaction.
    em.flush();
    // Another transaction may have updated a row already present in this context.
    em.refresh(root, LockModeType.PESSIMISTIC_WRITE);
    return root;
  }

  public ObjectNode templates(boolean includeArchived) {
    ObjectNode out = JSON.createObjectNode();
    ArrayNode items = out.putArray("items");
    List<SeriesTemplate> templates = allTemplates().stream().filter(item -> includeArchived || !item.isArchived()).toList();
    if (templates.isEmpty()) return out;
    Map<String, Long> counts = new HashMap<>();
    for (Object[] row : em.createQuery("select templateUid,count(uid) from SeriesTemplateRevision group by templateUid", Object[].class).getResultList())
      counts.put((String) row[0], (Long) row[1]);
    Map<String, ObjectNode> heads = new HashMap<>();
    List<String> headIds = templates.stream().map(SeriesTemplate::getHeadRevisionUid).toList();
    for (SeriesTemplateRevision row : em.createQuery("from SeriesTemplateRevision where uid in :ids", SeriesTemplateRevision.class)
      .setParameter("ids", headIds).getResultList()) heads.put(row.getUid(), validateRevision(parse(row.getPayload())));
    for (SeriesTemplate item : templates) items.add(summary(item, heads.get(item.getHeadRevisionUid()), counts.getOrDefault(item.getUid(), 0L)));
    return out;
  }

  public ObjectNode template(String uid) {
    SeriesTemplate item = requireTemplate(uid);
    ObjectNode out = JSON.createObjectNode();
    out.set("template", summary(item));
    out.set("revisions", revisions(item.getUid()));
    return out;
  }

  public ObjectNode createTemplate(JsonNode input) {
    SeriesCatalogState root = catalog();
    String requestHash = requestHash("template:create", input);
    ObjectNode receipt = receipt("global", input, requestHash);
    if (receipt != null) return globalReplay(receipt);
    String uid = uuid(input, "templateUid");
    if (em.find(SeriesTemplate.class, uid) != null) throw conflict("template_exists", "母本身份已存在，请刷新后重试");
    String kind = text(input, "kind", 20, false);
    fields(kind);
    String now = now();
    SeriesTemplate item = new SeriesTemplate();
    item.setUid(uid); item.setKind(kind); item.setCreatedAt(now); item.setUpdatedAt(now);
    ObjectNode revision = newRevision(item, null, 1, input, now);
    item.setHeadRevisionUid(revision.path("uid").asText());
    item.setLockVersion(1);
    em.persist(item); persistRevision(revision);
    finishCatalog(root);
    ObjectNode result = JSON.createObjectNode().put("templateUid", uid).put("revisionUid", revision.path("uid").asText());
    saveReceipt("global", input, requestHash, result);
    return globalResult(result, false);
  }

  public ObjectNode publish(String uid, JsonNode input) {
    SeriesCatalogState root = catalog();
    String requestHash = requestHash("template:publish:" + uid, input);
    ObjectNode receipt = receipt("global", input, requestHash);
    if (receipt != null) return globalReplay(receipt);
    SeriesTemplate item = requireTemplate(uid);
    checkTemplateVersion(item, input, true);
    if (item.isArchived()) throw conflict("template_archived", "请先取消归档，再保存母本新版");
    long number = 0;
    for (JsonNode revision : revisions(uid)) number = Math.max(number, revision.path("number").asLong());
    ObjectNode revision = newRevision(item, item.getHeadRevisionUid(), increment(number), input, now());
    persistRevision(revision);
    item.setHeadRevisionUid(revision.path("uid").asText());
    changed(item);
    finishCatalog(root);
    ObjectNode result = JSON.createObjectNode().put("templateUid", uid).put("revisionUid", revision.path("uid").asText());
    saveReceipt("global", input, requestHash, result);
    return globalResult(result, false);
  }

  public ObjectNode selectHead(String uid, JsonNode input) {
    SeriesCatalogState root = catalog();
    String requestHash = requestHash("template:head:" + uid, input);
    ObjectNode receipt = receipt("global", input, requestHash);
    if (receipt != null) return globalReplay(receipt);
    SeriesTemplate item = requireTemplate(uid);
    checkTemplateVersion(item, input, true);
    ObjectNode revision = requireRevision(uuid(input, "revisionUid"));
    if (!uid.equals(revision.path("templateUid").asText())) throw missing();
    item.setHeadRevisionUid(revision.path("uid").asText());
    changed(item); finishCatalog(root);
    ObjectNode result = JSON.createObjectNode().put("templateUid", uid);
    saveReceipt("global", input, requestHash, result);
    return globalResult(result, false);
  }

  public ObjectNode archiveTemplate(String uid, JsonNode input) {
    SeriesCatalogState root = catalog();
    String requestHash = requestHash("template:archive:" + uid, input);
    ObjectNode receipt = receipt("global", input, requestHash);
    if (receipt != null) return globalReplay(receipt);
    SeriesTemplate item = requireTemplate(uid);
    checkTemplateVersion(item, input, false);
    item.setArchived(bool(input, "archived"));
    changed(item); finishCatalog(root);
    ObjectNode result = JSON.createObjectNode().put("templateUid", uid);
    saveReceipt("global", input, requestHash, result);
    return globalResult(result, false);
  }

  public ObjectNode exportLibrary(JsonNode input) {
    catalog();
    if (input != null) checkKeys(input, Set.of("templateUids"));
    Set<String> selected = null;
    if (input != null && input.has("templateUids")) {
      if (!input.path("templateUids").isArray()) throw bad("母本选择格式无效");
      selected = new LinkedHashSet<>();
      for (JsonNode id : input.path("templateUids")) {
        String uid = uuidValue(id);
        requireTemplate(uid);
        if (!selected.add(uid)) throw bad("母本选择不能重复");
      }
    }
    return fullPack(selected);
  }

  public ObjectNode importPreview(JsonNode input) {
    checkKeys(input, Set.of("package"));
    SeriesCatalogState root = catalog();
    ObjectNode pack = validatePack(input.path("package"));
    return preview(pack, root);
  }

  public ObjectNode importLibrary(JsonNode input) {
    SeriesCatalogState root = catalog();
    String requestHash = requestHash("library:import", input);
    ObjectNode receipt = receipt("global", input, requestHash);
    if (receipt != null) return receipt.put("replayed", true);
    ObjectNode pack = validatePack(input.path("package"));
    ObjectNode plan = preview(pack, root);
    if (!plan.path("planToken").asText().equals(text(input, "planToken", 64, true)))
      throw conflict("preview_stale", "母本库已变化，请重新预览导入");
    merge(pack);
    finishCatalog(root);
    ObjectNode result = JSON.createObjectNode()
      .put("createdTemplates", plan.path("createTemplates").asInt())
      .put("addedRevisions", plan.path("addRevisions").asInt())
      .put("reusedRevisions", plan.path("reuseRevisions").asInt())
      .put("preservedHeads", plan.path("preservedHeads").asInt());
    saveReceipt("global", input, requestHash, result);
    return result.put("replayed", false);
  }

  private ObjectNode preview(ObjectNode pack, SeriesCatalogState root) {
    identityAndCapacity(pack);
    ObjectNode out = JSON.createObjectNode();
    int create = 0, add = 0, reuse = 0, preserve = 0;
    ArrayNode heads = out.putArray("heads");
    for (JsonNode row : pack.path("templates")) {
      SeriesTemplate old = em.find(SeriesTemplate.class, row.path("uid").asText());
      if (old == null) create++;
      else {
        preserve++;
        heads.addObject().put("templateUid", old.getUid()).put("currentHeadRevisionUid", old.getHeadRevisionUid())
          .put("incomingHeadRevisionUid", row.path("headRevisionUid").asText());
      }
    }
    for (JsonNode row : pack.path("revisions")) {
      if (em.find(SeriesTemplateRevision.class, row.path("uid").asText()) == null) add++; else reuse++;
    }
    String packageHash = hash(pack);
    out.put("createTemplates", create).put("addRevisions", add).put("reuseRevisions", reuse).put("preservedHeads", preserve)
      .put("packageHash", packageHash)
      .put("planToken", hash(JSON.createObjectNode().put("packageHash", packageHash).put("catalogVersion", root.getVersion())));
    return out;
  }

  private void identityAndCapacity(ObjectNode incoming) {
    ObjectNode union = fullPack(null);
    for (JsonNode row : incoming.path("templates")) {
      SeriesTemplate old = em.find(SeriesTemplate.class, row.path("uid").asText());
      if (old == null) union.withArray("templates").add(row.deepCopy());
      else if (!old.getKind().equals(row.path("kind").asText()))
        throw conflict("source_identity", "同一母本身份的类型不同，导入未执行");
    }
    for (JsonNode row : incoming.path("revisions")) {
      SeriesTemplateRevision old = em.find(SeriesTemplateRevision.class, row.path("uid").asText());
      if (old == null) union.withArray("revisions").add(row.deepCopy());
      else if (!validateRevision(parse(old.getPayload())).equals(row))
        throw conflict("source_identity", "同一母本版本身份对应不同内容，导入未执行");
    }
    capacity(union);
  }

  private void merge(ObjectNode pack) {
    for (JsonNode row : pack.path("templates")) {
      if (em.find(SeriesTemplate.class, row.path("uid").asText()) != null) continue;
      SeriesTemplate item = new SeriesTemplate();
      item.setUid(row.path("uid").asText()); item.setKind(row.path("kind").asText());
      item.setHeadRevisionUid(row.path("headRevisionUid").asText()); item.setArchived(row.path("archived").asBoolean());
      item.setCreatedAt(now()); item.setUpdatedAt(now()); item.setLockVersion(1);
      em.persist(item);
    }
    for (JsonNode row : pack.path("revisions"))
      if (em.find(SeriesTemplateRevision.class, row.path("uid").asText()) == null) persistRevision((ObjectNode) row);
  }

  private ObjectNode newRevision(SeriesTemplate item, String parent, long number, JsonNode input, String time) {
    ObjectNode revision = JSON.createObjectNode().put("uid", UUID.randomUUID().toString())
      .put("templateUid", item.getUid()).put("number", number).put("kind", item.getKind())
      .put("seriesName", text(input, "seriesName", 200, true)).put("authorStatus", status(input))
      .put("changeNote", text(input, "changeNote", 20_000, false)).put("createdAt", time);
    if (parent == null) revision.putNull("parentRevisionUid"); else revision.put("parentRevisionUid", parent);
    revision.set("payload", validatePayload(item.getKind(), input.path("payload")));
    revision.put("hash", revisionHash(revision));
    return validateRevision(revision);
  }

  private ObjectNode fullPack(Set<String> selected) {
    ObjectNode pack = emptyPack();
    Set<String> included = new HashSet<>();
    for (SeriesTemplate item : allTemplates()) {
      if (selected != null && !selected.contains(item.getUid())) continue;
      included.add(item.getUid());
      pack.withArray("templates").addObject().put("uid", item.getUid()).put("kind", item.getKind())
        .put("headRevisionUid", item.getHeadRevisionUid()).put("archived", item.isArchived());
    }
    for (SeriesTemplateRevision row : em.createQuery("from SeriesTemplateRevision order by templateUid,uid", SeriesTemplateRevision.class).getResultList())
      if (included.contains(row.getTemplateUid())) pack.withArray("revisions").add(validateRevision(parse(row.getPayload())));
    return pack;
  }

  private ObjectNode emptyPack() {
    ObjectNode pack = JSON.createObjectNode().put("format", "novel-assistant-series-v1").put("schemaVersion", 1).put("exportedAt", now());
    pack.putArray("templates"); pack.putArray("revisions");
    return pack;
  }

  private List<SeriesTemplate> allTemplates() {
    return em.createQuery("from SeriesTemplate order by createdAt,uid", SeriesTemplate.class).getResultList();
  }

  private ArrayNode revisions(String templateUid) {
    List<ObjectNode> items = new ArrayList<>();
    for (SeriesTemplateRevision row : em.createQuery("from SeriesTemplateRevision where templateUid=:uid", SeriesTemplateRevision.class)
      .setParameter("uid", templateUid).getResultList()) items.add(validateRevision(parse(row.getPayload())));
    items.sort(Comparator.<ObjectNode>comparingLong(row -> row.path("number").asLong())
      .thenComparing(row -> row.path("createdAt").asText()).thenComparing(row -> row.path("uid").asText()));
    return JSON.createArrayNode().addAll(items);
  }

  private ObjectNode summary(SeriesTemplate item) {
    ObjectNode head = requireRevision(item.getHeadRevisionUid());
    long count = em.createQuery("select count(uid) from SeriesTemplateRevision where templateUid=:uid", Long.class)
      .setParameter("uid", item.getUid()).getSingleResult();
    return summary(item, head, count);
  }

  private ObjectNode summary(SeriesTemplate item, ObjectNode head, long count) {
    if (head == null) throw bad("母本当前版本缺失");
    return JSON.createObjectNode().put("uid", item.getUid()).put("kind", item.getKind())
      .put("seriesName", head.path("seriesName").asText()).put("name", head.path("payload").path("name").asText())
      .put("headRevisionUid", item.getHeadRevisionUid()).put("headRevisionNumber", head.path("number").asLong())
      .put("lockVersion", item.getLockVersion()).put("archived", item.isArchived())
      .put("revisionCount", count).put("createdAt", item.getCreatedAt()).put("updatedAt", item.getUpdatedAt());
  }

  private SeriesTemplate requireTemplate(String uid) {
    uuidValue(JSON.getNodeFactory().textNode(uid));
    SeriesTemplate item = em.find(SeriesTemplate.class, uid);
    if (item == null) throw missing();
    return item;
  }

  private ObjectNode requireRevision(String uid) {
    uuidValue(JSON.getNodeFactory().textNode(uid));
    SeriesTemplateRevision revision = em.find(SeriesTemplateRevision.class, uid);
    if (revision == null) throw missing();
    return validateRevision(parse(revision.getPayload()));
  }

  private void persistRevision(ObjectNode revision) {
    SeriesTemplateRevision row = new SeriesTemplateRevision();
    row.setUid(revision.path("uid").asText()); row.setTemplateUid(revision.path("templateUid").asText());
    row.setPayload(stringify(revision)); em.persist(row);
  }

  private void changed(SeriesTemplate item) {
    item.setLockVersion(increment(item.getLockVersion())); item.setUpdatedAt(now());
  }

  private void finishCatalog(SeriesCatalogState root) {
    em.flush();
    capacity(fullPack(null));
    root.setVersion(increment(root.getVersion()));
  }

  private void capacity(JsonNode pack) {
    if (stringify(pack).getBytes(StandardCharsets.UTF_8).length > CATALOG_BYTES)
      throw new SeriesProblem(413, "series_capacity", "完整母本库已达到15 MiB上限；此次修改未保存，已有历史仍保留");
  }

  private void checkTemplateVersion(SeriesTemplate item, JsonNode input, boolean head) {
    if (counter(input, "expectedLockVersion") != item.getLockVersion() ||
      (head && !item.getHeadRevisionUid().equals(uuid(input, "expectedHeadRevisionUid"))))
      throw conflict("version_conflict", "母本已被其他窗口修改，请刷新比较后重试");
  }

  private ObjectNode globalReplay(ObjectNode result) { return globalResult(result, true); }
  private ObjectNode globalResult(ObjectNode result, boolean replayed) {
    ObjectNode out = JSON.createObjectNode().put("replayed", replayed);
    out.set("template", summary(requireTemplate(result.path("templateUid").asText())));
    if (result.has("revisionUid")) out.set("revision", requireRevision(result.path("revisionUid").asText()));
    return out;
  }

  public ObjectNode get(Long novelId) { return state(writing.book(novelId), true); }

  public ObjectNode world(Long novelId, String uid, String action, JsonNode input) {
    return mutate(novelId, "world:" + action + ":" + uid, input, current -> {
      if (action.equals("create")) {
        String worldUid = uuid(input, "worldUid");
        if (find(current.path("worlds"), worldUid) != null) throw conflict("world_exists", "这个世界已存在");
        current.withArray("worlds").addObject().put("uid", worldUid)
          .put("name", text(input, "name", 100, true)).put("description", text(input, "description", 20_000, false));
      } else {
        ObjectNode world = requireRow(current.path("worlds"), uid);
        if (action.equals("edit")) {
          world.put("name", text(input, "name", 100, true)).put("description", text(input, "description", 20_000, false));
        } else if (action.equals("remove")) {
          if (current.path("worlds").size() <= 1) throw conflict("world_in_use", "作品至少保留一个世界");
          for (JsonNode copy : current.path("copies"))
            if (uid.equals(copy.path("universeUid").asText())) throw conflict("world_in_use", "该世界仍有设定副本，归档副本也保留来源，请勿删除");
          ArrayNode next = JSON.createArrayNode();
          for (JsonNode row : current.path("worlds")) if (!uid.equals(row.path("uid").asText())) next.add(row);
          current.set("worlds", next);
        } else throw bad("未知世界操作");
      }
    });
  }

  public ObjectNode createCopy(Long novelId, JsonNode input) {
    return mutate(novelId, "copy:create", input, current -> {
      String uid = uuid(input, "copyUid");
      if (find(current.path("copies"), uid) != null) throw conflict("copy_exists", "副本身份已存在，请刷新核对");
      String universe = uuid(input, "universeUid"); requireRow(current.path("worlds"), universe);
      SeriesTemplate source = requireTemplate(uuid(input, "templateUid"));
      if (source.isArchived()) throw conflict("template_archived", "已归档的母本不能创建新副本，请先取消归档");
      ObjectNode revision = requireRevision(uuid(input, "revisionUid"));
      if (!source.getUid().equals(revision.path("templateUid").asText())) throw missing();
      embed(current, revision);
      String time = now();
      ObjectNode copy = current.withArray("copies").addObject().put("uid", uid).put("kind", source.getKind())
        .put("universeUid", universe).put("planet", text(input, "planet", 200, false))
        .put("authorStatus", "draft").put("baselineRevisionUid", revision.path("uid").asText())
        .put("archived", false).put("createdAt", time).put("updatedAt", time);
      copy.set("content", revision.path("payload").deepCopy());
      copy.putObject("origin").put("templateUid", source.getUid()).put("revisionUid", revision.path("uid").asText()).put("copiedAt", time);
      copy.set("lastReview", review(revision, fields(source.getKind()), "copy", time));
      ObjectNode origins = copy.putObject("fieldOrigins");
      for (String field : fields(source.getKind())) origins.putObject(field).put("kind", "source").put("revisionUid", revision.path("uid").asText());
      copy.putArray("history");
    });
  }

  public ObjectNode duplicateCopy(Long novelId, String sourceUid, JsonNode input) {
    return mutate(novelId, "copy:duplicate:" + sourceUid, input, current -> {
      ObjectNode source = requireRow(current.path("copies"), sourceUid);
      String uid = uuid(input, "copyUid"), world = uuid(input, "universeUid");
      if (find(current.path("copies"), uid) != null) throw conflict("copy_exists", "副本身份已存在，请刷新核对");
      requireRow(current.path("worlds"), world);
      ObjectNode copy = source.deepCopy();
      copy.put("uid", uid).put("universeUid", world).put("planet", text(input, "planet", 200, false))
        .put("authorStatus", "draft").put("archived", false).put("createdAt", now()).put("updatedAt", now());
      copy.putArray("history");
      current.withArray("copies").add(copy);
    });
  }

  public ObjectNode editCopy(Long novelId, String uid, JsonNode input) {
    return mutate(novelId, "copy:edit:" + uid, input, current -> {
      ObjectNode copy = requireRow(current.path("copies"), uid);
      ObjectNode content = validatePayload(copy.path("kind").asText(), input.path("content"));
      addHistory(copy, "edit");
      for (String field : fields(copy.path("kind").asText())) {
        if (!content.path(field).equals(copy.path("content").path(field)))
          ((ObjectNode) copy.path("fieldOrigins")).putObject(field).put("kind", "local").putNull("revisionUid");
      }
      copy.set("content", content);
      copy.put("authorStatus", status(input)).put("planet", text(input, "planet", 200, false)).put("updatedAt", now());
    });
  }

  public ObjectNode archiveCopy(Long novelId, String uid, JsonNode input) {
    return mutate(novelId, "copy:archive:" + uid, input, current -> {
      ObjectNode copy = requireRow(current.path("copies"), uid);
      boolean archived = bool(input, "archived");
      addHistory(copy, "archive");
      copy.put("archived", archived).put("updatedAt", now());
    });
  }

  public ObjectNode restoreCopy(Long novelId, String uid, JsonNode input) {
    return mutate(novelId, "copy:restore:" + uid, input, current -> {
      ObjectNode copy = requireRow(current.path("copies"), uid);
      ObjectNode history = requireRow(copy.path("history"), uuid(input, "historyUid"));
      ObjectNode before = ((ObjectNode) history.path("before")).deepCopy();
      addHistory(copy, "restore");
      before.fields().forEachRemaining(field -> copy.set(field.getKey(), field.getValue()));
      copy.put("updatedAt", now());
    });
  }

  public ObjectNode compare(Long novelId, String uid, JsonNode input) {
    checkKeys(input, Set.of("revisionUid"));
    ObjectNode current = state(writing.book(novelId), true);
    ObjectNode copy = requireRow(current.path("copies"), uid);
    return comparison(current, copy, candidate(current, copy, uuid(input, "revisionUid")));
  }

  public ObjectNode adopt(Long novelId, String uid, JsonNode input, boolean reviewOnly) {
    return mutate(novelId, "copy:" + (reviewOnly ? "review:" : "adopt:") + uid, input, current -> {
      ObjectNode copy = requireRow(current.path("copies"), uid);
      if (!hash(copy).equals(text(input, "copyHash", 64, true)) ||
        !copy.path("baselineRevisionUid").asText().equals(uuid(input, "baselineRevisionUid")))
        throw conflict("preview_stale", "本作副本已变化，请重新比较后采用");
      ObjectNode revision = candidate(current, copy, uuid(input, "revisionUid"));
      Set<String> chosen = new LinkedHashSet<>();
      List<String> allowed = fields(copy.path("kind").asText());
      if (!reviewOnly) {
        if (!input.path("selectedFields").isArray() || input.path("selectedFields").isEmpty()) throw bad("请至少选择一个采用字段");
        for (JsonNode field : input.path("selectedFields"))
          if (!field.isTextual() || !allowed.contains(field.asText()) || !chosen.add(field.asText())) throw bad("采用字段无效或重复");
      } else if (input.has("selectedFields")) throw bad("仅记录比较不接受采用字段");
      embed(current, revision);
      addHistory(copy, reviewOnly ? "review" : "adopt");
      boolean changed = false;
      for (String field : chosen) {
        if (!copy.path("content").path(field).equals(revision.path("payload").path(field))) changed = true;
        ((ObjectNode) copy.path("content")).set(field, revision.path("payload").path(field).deepCopy());
        ((ObjectNode) copy.path("fieldOrigins")).putObject(field).put("kind", "source").put("revisionUid", revision.path("uid").asText());
      }
      if (changed) copy.put("authorStatus", "draft");
      copy.put("baselineRevisionUid", revision.path("uid").asText()).put("updatedAt", now());
      copy.set("lastReview", review(revision, chosen, reviewOnly ? "review" : "adopt", now()));
    });
  }

  private ObjectNode comparison(ObjectNode current, ObjectNode copy, ObjectNode revision) {
    ObjectNode base = requireRow(current.path("sourceRevisions"), copy.path("baselineRevisionUid").asText());
    ObjectNode out = JSON.createObjectNode().put("copyUid", copy.path("uid").asText()).put("epoch", current.path("epoch").asText())
      .put("expectedVersion", current.path("version").asLong()).put("copyHash", hash(copy))
      .put("baselineRevisionUid", base.path("uid").asText());
    out.set("revision", revision.deepCopy());
    ArrayNode differences = out.putArray("fields");
    for (String field : fields(copy.path("kind").asText())) {
      String b = base.path("payload").path(field).asText(), local = copy.path("content").path(field).asText(), incoming = revision.path("payload").path(field).asText();
      boolean sourceChanged = !incoming.equals(b), localChanged = !local.equals(b);
      differences.addObject().put("key", field).put("base", b).put("local", local).put("incoming", incoming)
        .put("sourceChanged", sourceChanged).put("localChanged", localChanged)
        .put("conflict", sourceChanged && localChanged && !local.equals(incoming)).put("different", !local.equals(incoming));
    }
    return out;
  }

  private ObjectNode candidate(ObjectNode current, ObjectNode copy, String revisionUid) {
    ObjectNode source = find(current.path("sourceRevisions"), revisionUid);
    if (source == null) source = requireRevision(revisionUid);
    if (!source.path("templateUid").equals(copy.path("origin").path("templateUid")) || !source.path("kind").equals(copy.path("kind"))) throw missing();
    return source;
  }

  private ObjectNode review(ObjectNode revision, Collection<String> adopted, String mode, String time) {
    ObjectNode review = JSON.createObjectNode().put("revisionUid", revision.path("uid").asText()).put("mode", mode).put("reviewedAt", time);
    ArrayNode chosen = review.putArray("adoptedFields"), kept = review.putArray("keptFields");
    for (String field : fields(revision.path("kind").asText())) {
      if (adopted.contains(field)) chosen.add(field); else kept.add(field);
    }
    return review;
  }

  private void embed(ObjectNode current, ObjectNode incoming) {
    ObjectNode revision = incoming;
    Set<String> seen = new HashSet<>();
    while (revision != null) {
      String uid = revision.path("uid").asText();
      if (!seen.add(uid)) throw bad("来源版本出现循环");
      ObjectNode existing = find(current.path("sourceRevisions"), uid);
      if (existing != null) {
        if (!existing.equals(revision)) throw conflict("source_identity", "来源版本身份与已保留内容不一致");
        break; // A previously validated source includes its complete ancestry.
      }
      current.withArray("sourceRevisions").add(revision.deepCopy());
      revision = revision.path("parentRevisionUid").isNull() ? null : requireRevision(revision.path("parentRevisionUid").asText());
    }
  }

  private ObjectNode snapshot(ObjectNode copy) {
    ObjectNode snapshot = copy.deepCopy();
    snapshot.remove(List.of("uid", "kind", "origin", "createdAt", "updatedAt", "history"));
    return snapshot;
  }

  private void addHistory(ObjectNode copy, String action) {
    ObjectNode before = snapshot(copy);
    copy.withArray("history").addObject().put("uid", UUID.randomUUID().toString()).put("createdAt", now()).put("action", action).set("before", before);
  }

  private ObjectNode mutate(Long novelId, String operation, JsonNode input, Consumer<ObjectNode> mutation) {
    WritingBook book = writing.book(novelId);
    ObjectNode current = state(book, true);
    if (!current.path("epoch").asText().equals(uuid(input, "epoch"))) throw conflict("epoch_conflict", "作品已恢复，请重新载入设定后再保存");
    String scope = "novel:" + novelId + ":" + current.path("epoch").asText();
    String requestHash = requestHash(operation, input);
    ObjectNode saved = receipt(scope, input, requestHash);
    if (saved != null) return novelResult(current, true, saved.path("resultVersion").asLong());
    if (counter(input, "expectedVersion") != current.path("version").asLong())
      throw conflict("version_conflict", "本作设定已被其他窗口修改，请刷新比较后重试");
    mutation.accept(current);
    current.put("version", increment(current.path("version").asLong()));
    ObjectNode checked = validateState(current, true);
    book.setSeriesData(stringify(checked)); writing.touch(book, false);
    ObjectNode result = JSON.createObjectNode().put("resultVersion", checked.path("version").asLong());
    saveReceipt(scope, input, requestHash, result);
    return novelResult(checked, false, checked.path("version").asLong());
  }

  private ObjectNode novelResult(ObjectNode state, boolean replayed, long resultVersion) {
    ObjectNode result = JSON.createObjectNode().put("replayed", replayed).put("resultVersion", resultVersion);
    result.set("state", state); return result;
  }

  private ObjectNode state(WritingBook book, boolean initialize) {
    if (book.getSeriesData() != null) return validateState(parse(book.getSeriesData()), true);
    ObjectNode empty = emptyState();
    if (initialize) book.setSeriesData(stringify(empty));
    return empty;
  }

  private ObjectNode find(JsonNode rows, String uid) {
    for (JsonNode row : rows) if (uid.equals(row.path("uid").asText())) return (ObjectNode) row;
    return null;
  }
  private ObjectNode requireRow(JsonNode rows, String uid) {
    uuidValue(JSON.getNodeFactory().textNode(uid));
    ObjectNode found = find(rows, uid); if (found == null) throw missing(); return found;
  }

  /** Complete novel-local state and exactly its source ancestry, without receipts. */
  public ObjectNode exportNovel(Long novelId) {
    ObjectNode document = state(writing.book(novelId), true);
    ObjectNode pack = sourcePack(document);
    document.remove("epoch");
    ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1);
    result.set("document", document); result.set("library", pack);
    return validateBackup(result);
  }

  private ObjectNode sourcePack(ObjectNode document) {
    ObjectNode pack = emptyPack();
    pack.set("revisions", document.path("sourceRevisions").deepCopy());
    Map<String, String> heads = new TreeMap<>();
    for (JsonNode copy : document.path("copies")) heads.put(copy.path("origin").path("templateUid").asText(), copy.path("baselineRevisionUid").asText());
    for (var entry : heads.entrySet()) {
      ObjectNode source = requireRow(document.path("sourceRevisions"), entry.getValue());
      pack.withArray("templates").addObject().put("uid", entry.getKey()).put("kind", source.path("kind").asText())
        .put("headRevisionUid", entry.getValue()).put("archived", false);
    }
    return pack;
  }

  /** Called inside the outer restore transaction before deleting data or images. */
  public void preflightRestore(Long novelId, JsonNode root) {
    requireNovelLock(novelId);
    WritingBook book = em.find(WritingBook.class, novelId);
    ObjectNode current = book == null ? emptyState() : state(book, false);
    JsonNode supplied = root.path("series");
    if (supplied.isMissingNode()) {
      if (root.path("schemaVersion").asInt(1) >= 4) throw bad("新版备份缺少系列设定区段");
      if (book != null && book.getSeriesData() != null && hasData(parse(book.getSeriesData())))
        throw conflict("legacy_series_missing", "旧备份没有系列设定，不能覆盖本作已有内容；请恢复到新作品");
      restoredState(current, null);
      return;
    }
    ObjectNode checked = validateBackup(supplied);
    restoredState(current, (ObjectNode) checked.path("document"));
    catalog();
    identityAndCapacity((ObjectNode) checked.path("library"));
  }

  /** Imports only this novel's document; shared heads and other novels are preserved. */
  public void restoreNovel(Long novelId, JsonNode supplied) {
    WritingBook book = writing.book(novelId);
    ObjectNode current = state(book, false);
    ObjectNode replacement;
    if (supplied == null || supplied.isMissingNode()) {
      if (hasData(current)) throw conflict("legacy_series_missing", "旧备份没有系列设定，请恢复到新作品");
      replacement = restoredState(current, null);
    } else {
      ObjectNode checked = validateBackup(supplied);
      replacement = restoredState(current, (ObjectNode) checked.path("document"));
      ObjectNode pack = (ObjectNode) checked.path("library");
      SeriesCatalogState root = catalog();
      identityAndCapacity(pack);
      boolean changed = false;
      for (JsonNode row : pack.path("revisions")) if (em.find(SeriesTemplateRevision.class, row.path("uid").asText()) == null) changed = true;
      merge(pack);
      if (changed) finishCatalog(root);
    }
    book.setSeriesData(stringify(validateState(replacement, true)));
    writing.touch(book, false);
  }

  private ObjectNode restoredState(ObjectNode current, ObjectNode imported) {
    ObjectNode replacement = imported == null ? emptyState() : imported.deepCopy();
    replacement.put("version", increment(Math.max(current.path("version").asLong(), replacement.path("version").asLong())));
    replacement.put("epoch", UUID.randomUUID().toString());
    return validateState(replacement, true);
  }

  private void requireNovelLock(Long novelId) {
    if (novelId == null || em.find(Novel.class, novelId, LockModeType.PESSIMISTIC_WRITE) == null) throw missing();
  }

  private String requestHash(String operation, JsonNode input) {
    if (input == null || !input.isObject()) throw bad("请求内容无效");
    Set<String> allowed = new HashSet<>(Set.of("mutationId"));
    if (operation.startsWith("template:create")) allowed.addAll(Set.of("templateUid", "kind", "seriesName", "payload", "authorStatus", "changeNote"));
    else if (operation.startsWith("template:publish:")) allowed.addAll(Set.of("expectedLockVersion", "expectedHeadRevisionUid", "seriesName", "payload", "authorStatus", "changeNote"));
    else if (operation.startsWith("template:head:")) allowed.addAll(Set.of("expectedLockVersion", "expectedHeadRevisionUid", "revisionUid"));
    else if (operation.startsWith("template:archive:")) allowed.addAll(Set.of("expectedLockVersion", "archived"));
    else if (operation.equals("library:import")) allowed.addAll(Set.of("planToken", "package"));
    else {
      allowed.addAll(Set.of("epoch", "expectedVersion"));
      if (operation.startsWith("world:create:")) allowed.addAll(Set.of("worldUid", "name", "description"));
      else if (operation.startsWith("world:edit:")) allowed.addAll(Set.of("name", "description"));
      else if (operation.equals("copy:create")) allowed.addAll(Set.of("copyUid", "templateUid", "revisionUid", "universeUid", "planet"));
      else if (operation.startsWith("copy:duplicate:")) allowed.addAll(Set.of("copyUid", "universeUid", "planet"));
      else if (operation.startsWith("copy:edit:")) allowed.addAll(Set.of("content", "authorStatus", "planet"));
      else if (operation.startsWith("copy:archive:")) allowed.add("archived");
      else if (operation.startsWith("copy:restore:")) allowed.add("historyUid");
      else if (operation.startsWith("copy:adopt:") || operation.startsWith("copy:review:")) {
        allowed.addAll(Set.of("copyHash", "baselineRevisionUid", "revisionUid"));
        if (operation.startsWith("copy:adopt:")) allowed.add("selectedFields");
      }
    }
    checkKeys(input, allowed);
    uuid(input, "mutationId");
    ObjectNode signed = JSON.createObjectNode().put("operation", operation);
    signed.set("body", input);
    return hash(signed);
  }

  private ObjectNode receipt(String scope, JsonNode input, String requestHash) {
    SeriesMutationReceipt saved = em.find(SeriesMutationReceipt.class, receiptId(scope, uuid(input, "mutationId")));
    if (saved == null) return null;
    if (!scope.equals(saved.getScope()) || !requestHash.equals(saved.getRequestHash()))
      throw conflict("mutation_reused", "该请求标识已用于其他内容，请核对保存结果后重试");
    return parse(saved.getResult());
  }

  private void saveReceipt(String scope, JsonNode input, String requestHash, JsonNode result) {
    String mutation = uuid(input, "mutationId");
    SeriesMutationReceipt saved = new SeriesMutationReceipt();
    saved.setId(receiptId(scope, mutation)); saved.setScope(scope); saved.setMutationId(mutation);
    saved.setRequestHash(requestHash); saved.setResult(stringify(result)); em.persist(saved);
  }

  private String receiptId(String scope, String mutation) {
    return hash(JSON.createObjectNode().put("scope", scope).put("mutationId", mutation));
  }

  private ObjectNode parse(String content) {
    try { return (ObjectNode) JSON.readTree(content); }
    catch (Exception invalid) { throw bad("设定数据格式无效"); }
  }
  private static String now() { return Instant.now().toString(); }
  private static SeriesProblem bad(String message) { return new SeriesProblem(400, "invalid_series", message); }
  private static SeriesProblem missing() { return new SeriesProblem(404, "series_not_found", "当前范围内未找到这条设定"); }
  private static SeriesProblem conflict(String code, String message) { return new SeriesProblem(409, code, message); }
  private static String text(JsonNode node, String key, int max, boolean required) {
    JsonNode value = node.path(key);
    if (!value.isTextual()) throw bad("字段格式或长度无效：" + key);
    String out = value.asText();
    if (required) { out = out.strip(); if (out.isEmpty()) throw bad("请填写：" + key); }
    if (out.length() > max) throw bad("字段格式或长度无效：" + key);
    return out;
  }
  private static String uuid(JsonNode input, String field) { return uuidValue(input.path(field)); }
  private static String uuidValue(JsonNode node) {
    if (!node.isTextual()) throw bad("设定身份格式无效");
    try { String value = UUID.fromString(node.asText()).toString(); if (!value.equals(node.asText())) throw bad("设定身份格式无效"); return value; }
    catch (IllegalArgumentException invalid) { throw bad("设定身份格式无效"); }
  }
  private static boolean bool(JsonNode node, String key) {
    if (!node.path(key).isBoolean()) throw bad("字段必须是布尔值：" + key);
    return node.path(key).asBoolean();
  }
  private static long counter(JsonNode node, String key) {
    JsonNode value = node.path(key);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > SAFE_INTEGER)
      throw bad("版本号无效：" + key);
    return value.asLong();
  }
  private static long increment(long value) {
    if (value >= SAFE_INTEGER) throw new SeriesProblem(413, "series_capacity", "版本计数达到上限，修改未执行");
    return value + 1;
  }
  private static String status(JsonNode input) {
    String value = text(input, "authorStatus", 20, false);
    if (!Set.of("draft", "confirmed").contains(value)) throw bad("作者确认状态无效");
    return value;
  }
  private static void checkKeys(JsonNode input, Set<String> allowed) {
    if (input == null || !input.isObject()) throw bad("请求内容必须是对象");
    Iterator<String> names = input.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      if (!allowed.contains(field)) throw bad("请求包含不支持的字段：" + field);
    }
  }
}
