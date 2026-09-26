package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class WritingService {

  @PersistenceContext
  private EntityManager em;

  public static final Map<String, Class<? extends NovelOwned>> TYPES = Map.of(
    "characters",
    Character.class,
    "timeline",
    TimelineEvent.class,
    "foreshadows",
    Foreshadow.class,
    "outlines",
    Outline.class,
    "scenes",
    Scene.class,
    "worldview",
    WorldviewEntry.class,
    "map",
    MapLocation.class,
    "tags",
    Tag.class
  );

  public WritingBook book(Long novelId) {
    // The parent row serializes first creation and structural changes across devices.
    if (
      em.find(Novel.class, novelId, LockModeType.PESSIMISTIC_WRITE) == null
    ) throw missing();
    WritingBook b = em.find(WritingBook.class, novelId);
    if (b == null) {
      b = new WritingBook();
      b.setNovelId(novelId);
      b.setUid(UUID.randomUUID().toString());
      em.persist(b);
    }
    return b;
  }

  public List<WritingChapter> chapters(Long id) {
    return em
      .createQuery(
        "from WritingChapter where novelId=:id order by position,id",
        WritingChapter.class
      )
      .setParameter("id", id)
      .getResultList();
  }

  @Transactional(readOnly = true)
  public ObjectNode summaries() {
    return summaries(java.time.LocalDate.now().toString());
  }

  @Transactional(readOnly = true)
  public ObjectNode summaries(String date) {
    try {
      java.time.LocalDate.parse(date);
    } catch (RuntimeException e) {
      throw bad("日期格式无效");
    }
    ObjectNode out = JSON.createObjectNode();
    for (Object[] row : em
      .createQuery(
        "select novelId,count(uid),sum(wordCount),max(updatedAt) from WritingChapter where deleted=false group by novelId",
        Object[].class
      )
      .getResultList())
      out
        .putObject(row[0].toString())
        .put("chapters", ((Number) row[1]).longValue())
        .put("words", ((Number) row[2]).longValue())
        .put("updatedAt", row[3] == null ? null : row[3].toString());
    for (Object[] row : em
      .createQuery(
        "select novelId,uid,title from WritingChapter where deleted=false order by updatedAt desc,id desc",
        Object[].class
      )
      .getResultList()) {
      if (
        !(out.path(row[0].toString()) instanceof ObjectNode summary)
      ) continue;
      if (!summary.has("lastChapter")) summary
        .putObject("lastChapter")
        .put("uid", row[1].toString())
        .put("title", row[2].toString());
    }
    for (WritingBook book : em
      .createQuery("from WritingBook", WritingBook.class)
      .getResultList()) {
      ObjectNode summary = out.has(book.getNovelId().toString())
        ? (ObjectNode) out.path(book.getNovelId().toString())
        : out
          .putObject(book.getNovelId().toString())
          .put("words", 0)
          .put("chapters", 0);
      summary.put(
        "dailyGoal",
        parse(book.getPreferences()).path("dailyGoal").asInt(2000)
      );
      summary.put("todayNet", 0);
    }
    for (Object[] row : em
      .createQuery(
        "select novelId,payload from WritingSession where payload like :date",
        Object[].class
      )
      .setParameter("date", "%\"date\":\"" + date + "\"%")
      .getResultList()) {
      JsonNode payload = parse((String) row[1]);
      if (
        !date.equals(payload.path("date").asText()) ||
        !out.has(row[0].toString())
      ) continue;
      ObjectNode summary = (ObjectNode) out.path(row[0].toString());
      summary.put(
        "todayNet",
        summary.path("todayNet").asLong() + payload.path("net").asLong()
      );
    }
    return out;
  }

  public WritingChapter chapter(Long id, String uid) {
    return em
      .createQuery(
        "from WritingChapter where novelId=:id and uid=:uid",
        WritingChapter.class
      )
      .setParameter("id", id)
      .setParameter("uid", uid)
      .getResultStream()
      .findFirst()
      .orElseThrow(WritingService::missing);
  }

  public ObjectNode workspace(Long id) {
    WritingBook b = book(id);
    ObjectNode out = JSON.createObjectNode()
      .put("uid", b.getUid())
      .put("structureVersion", b.getStructureVersion())
      .put("changeSequence", b.getChangeSequence())
      .put("syncedSequence", b.getSyncedSequence());
    out.set("volumes", parse(b.getVolumes()));
    out.set("preferences", parse(b.getPreferences()));
    ArrayNode rows = out.putArray("chapters");
    // Avoid loading all manuscript bodies just to render the chapter tree.
    List<Object[]> values = em
      .createQuery(
        "select c.uid,c.volumeId,c.position,c.title,c.summary,c.status,c.goal,c.numbered,c.deleted,c.revision,c.wordCount,c.updatedAt,c.links from WritingChapter c where c.novelId=:id order by c.position,c.id",
        Object[].class
      )
      .setParameter("id", id)
      .getResultList();
    String[] keys = {
      "uid",
      "volumeId",
      "position",
      "title",
      "summary",
      "status",
      "goal",
      "numbered",
      "deleted",
      "revision",
      "wordCount",
      "updatedAt",
      "links",
    };
    for (Object[] value : values) {
      ObjectNode row = rows.addObject();
      for (int i = 0; i < keys.length; i++) row.set(
        keys[i],
        i == 12 ? parse((String) value[i]) : JSON.valueToTree(value[i])
      );
    }
    ArrayNode sessions = out.putArray("sessions");
    for (WritingSession s : em
      .createQuery(
        "from WritingSession where novelId=:id",
        WritingSession.class
      )
      .setParameter("id", id)
      .getResultList())
      sessions.add(parse(s.getPayload()));
    out.put("syncMessage", b.getSyncMessage());
    out.put(
      "lastSync",
      b.getLastSync() == null ? null : b.getLastSync().toString()
    );
    return out;
  }

  public ObjectNode detail(WritingChapter c) {
    ObjectNode n = JSON.valueToTree(c);
    n.remove(List.of("id", "novelId", "document"));
    n.set("doc", parse(c.getDocument()));
    n.set("links", parse(c.getLinks()));
    return n;
  }

  public ObjectNode get(Long id, String uid) {
    book(id);
    return detail(chapter(id, uid));
  }

  public ObjectNode create(Long id, JsonNode input) {
    WritingBook b = book(id);
    String uid = uuid(input.path("uid").asText());
    var old = em
      .createQuery(
        "from WritingChapter where novelId=:id and uid=:uid",
        WritingChapter.class
      )
      .setParameter("id", id)
      .setParameter("uid", uid)
      .getResultStream()
      .findFirst();
    if (old.isPresent()) return detail(old.get()); // Retry of the same create is idempotent.
    WritingChapter c = new WritingChapter();
    c.setNovelId(id);
    c.setUid(uid);
    c.setPosition(
      chapters(id)
          .stream()
          .mapToInt(WritingChapter::getPosition)
          .max()
          .orElse(-1) +
        1
    );
    apply(c, input, b);
    c.setUpdatedAt(LocalDateTime.now());
    em.persist(c);
    touch(b, true);
    snapshot(c, "创建章节");
    return detail(c);
  }

  public ObjectNode save(Long id, String uid, JsonNode input) {
    WritingBook b = book(id);
    WritingChapter c = chapter(id, uid);
    String mutation = uuid(input.path("mutationId").asText());
    if (mutation.equals(c.getMutationId())) return detail(c);
    if (
      !input.has("revision") ||
      input.path("revision").asLong(-1) != c.getRevision()
    ) throw conflict();
    boolean checkpoint = input.path("checkpoint").asBoolean();
    if (checkpoint) snapshot(c, "手动存档前");
    else snapshotIfDue(c, false);
    apply(c, input, b);
    c.setRevision(c.getRevision() + 1);
    c.setMutationId(mutation);
    c.setUpdatedAt(LocalDateTime.now());
    if (checkpoint) snapshot(c, "手动存档");
    touch(b, false);
    return detail(c);
  }

  private void apply(WritingChapter c, JsonNode input, WritingBook b) {
    c.setTitle(required(input, "title", 200));
    String volume = input.path("volumeId").asText("");
    if (!volume.isEmpty() && !hasVolume(b, volume)) throw bad("篇卷不存在");
    c.setVolumeId(volume.isBlank() ? null : volume);
    String status = input.path("status").asText("draft");
    if (
      !Set.of("draft", "writing", "revision", "final").contains(status)
    ) throw bad("章节状态无效");
    c.setStatus(status);
    c.setSummary(limited(input.path("summary").asText(""), 10000));
    c.setNotes(limited(input.path("notes").asText(""), 50000));
    int goal = input.path("goal").asInt(2000);
    if (goal < 0 || goal > 1_000_000) throw bad("目标字数无效");
    c.setGoal(goal);
    c.setNumbered(input.path("numbered").asBoolean(true));
    c.setDeleted(input.path("deleted").asBoolean(false));
    JsonNode doc = validate(input.has("doc") ? input.get("doc") : empty());
    c.setDocument(stringify(doc));
    c.setWordCount(count(text(doc)));
    JsonNode links = input.has("links")
      ? input.path("links")
      : JSON.createArrayNode();
    validateLinks(c.getNovelId(), links, parse(c.getLinks()));
    c.setLinks(stringify(links));
  }

  public void validateLinks(Long novelId, JsonNode links, JsonNode previous) {
    if (!links.isArray() || links.size() > 2000) throw bad(
      "关联数量或格式无效"
    );
    Set<String> old = new HashSet<>();
    for (JsonNode l : previous)
      old.add(l.path("type").asText() + ":" + l.path("targetId").asLong());
    Set<String> ids = new HashSet<>();
    for (JsonNode link : links) {
      uuid(link.path("uid").asText());
      if (!ids.add(link.path("uid").asText())) throw bad("关联标识重复");
      String type = link.path("type").asText();
      if (!TYPES.containsKey(type)) throw bad("关联类型无效");
      if (
        link.has("plannedRole") &&
        (!type.equals("foreshadows") ||
          !Set.of("laid", "hint", "developed", "revealed").contains(
            link.path("plannedRole").asText()
          ) ||
          !Set.of("reference", link.path("plannedRole").asText()).contains(
            link.path("role").asText()
          ))
      ) throw bad("伏笔计划作用无效");
      if (
        link.path("missing").asBoolean() && link.path("targetId").isNull()
      ) continue;
      long target = link.path("targetId").asLong();
      if (target <= 0) throw bad("关联目标无效");
      NovelOwned record = em.find(TYPES.get(type), target);
      if (record == null) {
        if (!old.contains(type + ":" + target)) throw bad("关联资料已不存在");
      } else if (!Objects.equals(record.getNovelId(), novelId)) throw bad(
        "不能关联其他作品的资料"
      );
      limited(link.path("title").asText(), 300);
      limited(link.path("blockId").asText(), 80);
      limited(link.path("excerpt").asText(), 2000);
      if (
        !Set.of(
          "reference",
          "viewpoint",
          "current",
          "laid",
          "hint",
          "developed",
          "revealed"
        ).contains(link.path("role").asText("reference"))
      ) throw bad("关联作用无效");
    }
  }

  public ObjectNode structure(Long id, JsonNode input) {
    WritingBook b = book(id);
    if (
      input.path("structureVersion").asLong(-1) != b.getStructureVersion()
    ) throw conflict();
    JsonNode volumes = input.path("volumes");
    if (!volumes.isArray() || volumes.size() > 1000) throw bad("篇卷格式无效");
    Set<String> seen = new HashSet<>();
    for (JsonNode v : volumes) {
      if (!seen.add(uuid(v.path("uid").asText()))) throw bad("篇卷重复");
      required(v, "title", 200);
    }
    List<WritingChapter> chapters = chapters(id);
    Map<String, WritingChapter> byId = new HashMap<>();
    chapters.forEach(c -> byId.put(c.getUid(), c));
    JsonNode order = input.path("chapters");
    if (!order.isArray() || order.size() != chapters.size()) throw conflict();
    int position = 0;
    for (JsonNode row : order) {
      WritingChapter c = byId.remove(row.path("uid").asText());
      if (c == null) throw bad("章节排序重复或无效");
      String v = row.path("volumeId").asText("");
      if (!v.isEmpty() && !seen.contains(v)) throw bad("篇卷不存在");
      if (
        c.getPosition() != position ||
        !Objects.equals(c.getVolumeId(), v.isEmpty() ? null : v)
      ) {
        c.setPosition(position);
        c.setVolumeId(v.isEmpty() ? null : v);
        c.setRevision(c.getRevision() + 1);
      }
      position++;
    }
    b.setVolumes(stringify(volumes));
    touch(b, true);
    return workspace(id);
  }

  public ObjectNode preferences(Long id, JsonNode input) {
    WritingBook b = book(id);
    if (
      input.path("structureVersion").asLong(-1) != b.getStructureVersion()
    ) throw conflict();
    JsonNode prefs = input.path("preferences");
    if (!prefs.isObject() || prefs.toString().length() > 100000) throw bad(
      "写作偏好无效"
    );
    b.setPreferences(stringify(prefs));
    touch(b, true);
    return workspace(id);
  }

  public ObjectNode split(Long id, String uid, JsonNode input) {
    WritingBook b = book(id);
    WritingChapter c = chapter(id, uid);
    String nextUid = uuid(input.path("uid").asText());
    var retry = em
      .createQuery(
        "from WritingChapter where novelId=:id and uid=:uid",
        WritingChapter.class
      )
      .setParameter("id", id)
      .setParameter("uid", nextUid)
      .getResultStream()
      .findFirst();
    if (retry.isPresent() && !nextUid.equals(uid)) return detail(retry.get());
    if (c.getRevision() != input.path("revision").asLong(-1)) throw conflict();
    JsonNode doc = parse(c.getDocument());
    ArrayNode nodes = (ArrayNode) doc.path("content");
    int at = input.path("index").asInt(-1);
    if (at < 1 || at >= nodes.size()) throw bad(
      "请将光标放在第二个或之后的段落，再拆分章节"
    );
    ObjectNode right = empty();
    ArrayNode tail = right.putArray("content");
    while (nodes.size() > at) tail.add(nodes.remove(at));
    Set<String> moved = blocks(right);
    ArrayNode leftLinks = JSON.createArrayNode(),
      rightLinks = JSON.createArrayNode();
    for (JsonNode link : parse(c.getLinks())) {
      if (moved.contains(link.path("blockId").asText())) rightLinks.add(link);
      else leftLinks.add(link);
    }
    ObjectNode next = detail(c);
    next.put("uid", nextUid);
    next.put("title", required(input, "title", 200));
    next.set("doc", right);
    next.set("links", rightLinks);
    next.put("notes", "");
    next.put("summary", "");
    snapshot(c, "拆分前");
    c.setDocument(stringify(doc));
    c.setLinks(stringify(leftLinks));
    c.setWordCount(count(text(doc)));
    c.setRevision(c.getRevision() + 1);
    c.setUpdatedAt(LocalDateTime.now());
    ObjectNode created = create(id, next);
    WritingChapter other = chapter(id, created.path("uid").asText());
    for (WritingChapter row : chapters(id))
      if (
        !row.getUid().equals(other.getUid()) &&
        row.getPosition() > c.getPosition()
      ) row.setPosition(row.getPosition() + 1);
    other.setPosition(c.getPosition() + 1);
    touch(b, true);
    return detail(other);
  }

  public ObjectNode merge(Long id, String uid, JsonNode input) {
    WritingBook b = book(id);
    WritingChapter first = chapter(id, uid),
      second = chapter(id, input.path("otherUid").asText());
    if (first == second || first.isDeleted() || second.isDeleted()) throw bad(
      "请选择两个有效章节"
    );
    if (
      first.getRevision() != input.path("revision").asLong(-1) ||
      second.getRevision() != input.path("otherRevision").asLong(-1)
    ) throw conflict();
    snapshot(first, "合并前");
    snapshot(second, "合并前");
    ObjectNode doc = (ObjectNode) parse(first.getDocument());
    JsonNode added = parse(second.getDocument());
    // IDs copied by duplicate/import can coincide; remap the incoming blocks and links together.
    Map<String, String> remap = new HashMap<>();
    Set<String> occupied = blocks(doc);
    remapBlocks(added, occupied, remap);
    ((ArrayNode) doc.path("content")).addAll((ArrayNode) added.path("content"));
    ArrayNode links = (ArrayNode) parse(first.getLinks());
    for (JsonNode l : parse(second.getLinks())) {
      ObjectNode copy = l.deepCopy();
      copy.put("uid", UUID.randomUUID().toString());
      String block = copy.path("blockId").asText();
      if (remap.containsKey(block)) copy.put("blockId", remap.get(block));
      links.add(copy);
    }
    first.setDocument(stringify(validate(doc)));
    first.setLinks(stringify(links));
    first.setWordCount(count(text(doc)));
    first.setNotes(first.getNotes() + "\n" + second.getNotes());
    first.setRevision(first.getRevision() + 1);
    first.setUpdatedAt(LocalDateTime.now());
    second.setDeleted(true);
    second.setRevision(second.getRevision() + 1);
    second.setUpdatedAt(LocalDateTime.now());
    touch(b, true);
    return detail(first);
  }

  private void remapBlocks(
    JsonNode node,
    Set<String> occupied,
    Map<String, String> remap
  ) {
    String id = node.path("attrs").path("id").asText();
    if (!id.isBlank() && !occupied.add(id)) {
      String replacement = UUID.randomUUID().toString();
      ((ObjectNode) node.path("attrs")).put("id", replacement);
      remap.put(id, replacement);
    }
    for (JsonNode child : node.path("content"))
      remapBlocks(child, occupied, remap);
  }

  private void snapshotIfDue(WritingChapter c, boolean force) {
    var rows = em
      .createQuery(
        "from WritingRevision where novelId=:n and chapterUid=:c order by id desc",
        WritingRevision.class
      )
      .setParameter("n", c.getNovelId())
      .setParameter("c", c.getUid())
      .setMaxResults(1)
      .getResultList();
    if (
      force ||
      rows.isEmpty() ||
      rows.get(0).getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5))
    ) snapshot(c, force ? "手动存档" : "自动历史");
  }

  public void snapshot(WritingChapter c, String label) {
    WritingRevision r = new WritingRevision();
    r.setNovelId(c.getNovelId());
    r.setChapterUid(c.getUid());
    r.setRevision(c.getRevision());
    r.setLabel(label);
    r.setSnapshot(stringify(detail(c)));
    r.setCreatedAt(LocalDateTime.now());
    em.persist(r);
  }

  public ArrayNode revisions(Long id, String uid) {
    chapter(id, uid);
    ArrayNode result = JSON.createArrayNode();
    for (WritingRevision r : em
      .createQuery(
        "from WritingRevision where novelId=:n and chapterUid=:c order by id desc",
        WritingRevision.class
      )
      .setParameter("n", id)
      .setParameter("c", uid)
      .setMaxResults(100)
      .getResultList())
      result
        .addObject()
        .put("id", r.getId())
        .put("revision", r.getRevision())
        .put("label", r.getLabel())
        .put("createdAt", r.getCreatedAt().toString());
    return result;
  }

  public JsonNode revision(Long id, String uid, Long revisionId) {
    WritingRevision r = em.find(WritingRevision.class, revisionId);
    if (
      r == null || !id.equals(r.getNovelId()) || !uid.equals(r.getChapterUid())
    ) throw missing();
    return parse(r.getSnapshot());
  }

  public void session(Long id, String uid, JsonNode payload) {
    WritingBook b = book(id);
    uuid(uid);
    if (
      !payload.isObject() ||
      !uid.equals(payload.path("uid").asText()) ||
      payload.toString().length() > 20000
    ) throw bad("统计记录无效");
    for (String key : List.of("typed", "pasted", "activeSeconds", "peak"))
      if (
        payload.path(key).asLong(-1) < 0 ||
        payload.path(key).asLong() > 100_000_000
      ) throw bad("统计数值无效");
    try {
      LocalDate.parse(payload.path("date").asText());
    } catch (Exception e) {
      throw bad("统计日期无效");
    }
    WritingSession s = em
      .createQuery(
        "from WritingSession where novelId=:n and uid=:u",
        WritingSession.class
      )
      .setParameter("n", id)
      .setParameter("u", uid)
      .getResultStream()
      .findFirst()
      .orElse(null);
    long sequence = payload.path("sequence").asLong(-1);
    if (sequence < 0) throw bad("统计版本无效");
    boolean fresh = s == null;
    if (fresh) {
      s = new WritingSession();
      s.setNovelId(id);
      s.setUid(uid);
    } else if (sequence <= s.getSequence()) return;
    s.setSequence(sequence);
    s.setPayload(stringify(payload));
    if (fresh) em.persist(s);
    touch(b, false);
  }

  public ArrayNode search(Long id, String query) {
    book(id);
    ArrayNode out = JSON.createArrayNode();
    if (query == null || query.isBlank()) return out;
    String q = query.toLowerCase(Locale.ROOT);
    for (WritingChapter c : chapters(id))
      if (!c.isDeleted()) {
        String body = text(parse(c.getDocument()));
        int at = body.toLowerCase(Locale.ROOT).indexOf(q);
        if (at >= 0 || c.getTitle().toLowerCase(Locale.ROOT).contains(q)) out
          .addObject()
          .put("uid", c.getUid())
          .put("title", c.getTitle())
          .put(
            "excerpt",
            body.substring(
              Math.max(0, at - 35),
              Math.min(body.length(), Math.max(0, at) + 180)
            )
          )
          .put("wordCount", c.getWordCount());
        if (out.size() >= 100) break;
      }
    return out;
  }

  public ArrayNode backlinks(Long id, String type, Long target) {
    WritingBook b = book(id);
    Map<String, Integer> volumeOrder = new HashMap<>();
    int rank = 0;
    for (JsonNode volume : parse(b.getVolumes()))
      volumeOrder.put(volume.path("uid").asText(), rank++);
    List<WritingChapter> ordered = new ArrayList<>(chapters(id));
    ordered.sort(
      Comparator.comparingInt((WritingChapter c) ->
        c.getVolumeId() == null
          ? -1
          : volumeOrder.getOrDefault(c.getVolumeId(), Integer.MAX_VALUE)
      )
        .thenComparingInt(WritingChapter::getPosition)
        .thenComparing(WritingChapter::getId)
    );
    ArrayNode out = JSON.createArrayNode();
    for (WritingChapter c : ordered) {
      if (c.isDeleted()) continue;
      List<JsonNode> links = new ArrayList<>();
      for (JsonNode link : parse(c.getLinks())) {
        if (
          link.path("type").asText().equals(type) &&
          link.path("targetId").asLong() == target
        ) links.add(link);
      }
      if (links.isEmpty()) continue;
      Map<String, Integer> blockOrder = new HashMap<>();
      for (String block : parse(c.getDocument()).findValuesAsText("id"))
        blockOrder.put(block, blockOrder.size());
      links.sort(
        Comparator.comparingInt(link -> {
          String anchor = link.path("blockId").asText();
          if (anchor.isBlank()) return -1;
          return blockOrder.getOrDefault(anchor, Integer.MAX_VALUE);
        })
      );
      for (JsonNode link : links) {
        ObjectNode row = (ObjectNode) link.deepCopy();
        row.put("chapterUid", c.getUid());
        row.put("chapterTitle", c.getTitle());
        row.put("position", c.getPosition());
        row.put(
          "anchorMissing",
          !link.path("blockId").asText().isBlank() &&
            !blockOrder.containsKey(link.path("blockId").asText())
        );
        out.add(row);
      }
    }
    return out;
  }

  public void touch(WritingBook book, boolean structure) {
    book.setChangeSequence(book.getChangeSequence() + 1);
    if (structure) book.setStructureVersion(book.getStructureVersion() + 1);
  }

  @Transactional(readOnly = true)
  public ObjectNode exportBackup(Long id) {
    ObjectNode result = JSON.createObjectNode();
    WritingBook b = em.find(WritingBook.class, id);
    result.set(
      "volumes",
      b == null ? JSON.createArrayNode() : parse(b.getVolumes())
    );
    result.set(
      "preferences",
      b == null ? JSON.createObjectNode() : parse(b.getPreferences())
    );
    result.put("uid", b == null ? "" : b.getUid());
    ArrayNode rows = result.putArray("chapters");
    for (WritingChapter c : chapters(id)) rows.add(detail(c));
    ArrayNode sessions = result.putArray("sessions");
    for (WritingSession s : em
      .createQuery(
        "from WritingSession where novelId=:id",
        WritingSession.class
      )
      .setParameter("id", id)
      .getResultList())
      sessions.add(parse(s.getPayload()));
    ArrayNode history = result.putArray("revisions");
    for (WritingRevision r : em
      .createQuery(
        "from WritingRevision where novelId=:id order by id desc",
        WritingRevision.class
      )
      .setParameter("id", id)
      .setMaxResults(2000)
      .getResultList()) {
      ObjectNode row = history
        .addObject()
        .put("chapterUid", r.getChapterUid())
        .put("revision", r.getRevision())
        .put("label", r.getLabel())
        .put("createdAt", r.getCreatedAt().toString());
      row.set("snapshot", parse(r.getSnapshot()));
    }
    return result;
  }

  public void protectLegacyRestore(Long id, JsonNode root) {
    if (!root.has("writing") && !chapters(id).isEmpty()) throw bad(
      "这个旧备份不含正文。请恢复到一个新建作品，避免清除现有正文及关联。"
    );
  }

  public static void validateBackup(JsonNode data) {
    if (!data.isObject()) throw new IllegalArgumentException("缺少写作备份");
    for (String key : List.of("volumes", "chapters", "sessions", "revisions"))
      if (!data.path(key).isArray()) throw new IllegalArgumentException(
        "写作备份缺少 " + key
      );
    if (
      !data.path("preferences").isObject()
    ) throw new IllegalArgumentException("写作偏好无效");
    Set<String> volumes = new HashSet<>(),
      chapters = new HashSet<>();
    for (JsonNode v : data.path("volumes")) {
      if (!volumes.add(uuid(v.path("uid").asText()))) throw bad("篇卷标识重复");
      required(v, "title", 200);
    }
    for (JsonNode c : data.path("chapters")) {
      if (!chapters.add(uuid(c.path("uid").asText()))) throw bad(
        "章节标识重复"
      );
      required(c, "title", 200);
      validate(c.path("doc"));
      if (!c.path("links").isArray()) throw bad("章节关联无效");
      String volume = c.path("volumeId").asText("");
      if (!volume.isBlank() && !volumes.contains(volume)) throw bad(
        "章节所属篇卷不存在"
      );
    }
    for (JsonNode r : data.path("revisions"))
      validate(r.path("snapshot").path("doc"));
  }

  public void restoreBackup(
    Long id,
    JsonNode input,
    Map<String, Map<Long, Long>> ids
  ) {
    if (input == null) return;
    validateBackup(input);
    WritingBook b = book(id);
    b.setVolumes(stringify(input.path("volumes")));
    ObjectNode preferences = input.path("preferences").deepCopy();
    ObjectNode aliases = JSON.createObjectNode();
    input
      .path("preferences")
      .path("aliases")
      .fields()
      .forEachRemaining(entry -> {
        try {
          Long target = ids
            .getOrDefault("characters", Map.of())
            .get(Long.parseLong(entry.getKey()));
          if (target != null) aliases.set(target.toString(), entry.getValue());
        } catch (NumberFormatException ignored) {}
      });
    preferences.set("aliases", aliases);
    b.setPreferences(stringify(preferences));
    Map<String, Long> prior = new HashMap<>();
    for (WritingChapter c : chapters(id)) {
      prior.put(c.getUid(), c.getRevision());
      snapshot(c, "恢复备份前");
      em.remove(c);
    }
    // Old resources were replaced. Keep historical prose and labels, never bind old IDs to a different record.
    for (WritingRevision history : em
      .createQuery(
        "from WritingRevision where novelId=:id",
        WritingRevision.class
      )
      .setParameter("id", id)
      .getResultList()) {
      ObjectNode snapshot = (ObjectNode) parse(history.getSnapshot());
      remapLinks((ArrayNode) snapshot.path("links"), Map.of());
      history.setSnapshot(stringify(snapshot));
    }
    em.flush();
    for (JsonNode c : input.path("chapters")) {
      ObjectNode row = c.deepCopy();
      remapLinks((ArrayNode) row.path("links"), ids);
      ObjectNode created = create(id, row);
      WritingChapter saved = chapter(id, created.path("uid").asText());
      saved.setPosition(c.path("position").asInt());
      saved.setRevision(
        Math.max(
            prior.getOrDefault(saved.getUid(), -1L),
            c.path("revision").asLong()
          ) +
          1
      );
    }
    for (JsonNode s : input.path("sessions"))
      session(id, s.path("uid").asText(), s);
    for (JsonNode h : input.path("revisions")) {
      ObjectNode snap = h.path("snapshot").deepCopy();
      remapLinks((ArrayNode) snap.path("links"), ids);
      String chapterUid = uuid(h.path("chapterUid").asText());
      LocalDateTime createdAt;
      try {
        createdAt = LocalDateTime.parse(h.path("createdAt").asText());
      } catch (Exception e) {
        createdAt = LocalDateTime.now();
      }
      // Repeated restores must not multiply the same historical snapshot.
      em
        .createQuery(
          "delete from WritingRevision where novelId=:id and chapterUid=:uid and revision=:revision and createdAt=:time"
        )
        .setParameter("id", id)
        .setParameter("uid", chapterUid)
        .setParameter("revision", h.path("revision").asLong())
        .setParameter("time", createdAt)
        .executeUpdate();
      WritingRevision r = new WritingRevision();
      r.setNovelId(id);
      r.setChapterUid(chapterUid);
      r.setRevision(h.path("revision").asLong());
      r.setSnapshot(stringify(snap));
      r.setLabel(h.path("label").asText("备份历史"));
      r.setCreatedAt(createdAt);
      em.persist(r);
    }
    touch(b, true);
  }

  private void remapLinks(ArrayNode links, Map<String, Map<Long, Long>> ids) {
    for (JsonNode link : links) {
      ObjectNode row = (ObjectNode) link;
      Long target = ids
        .getOrDefault(row.path("type").asText(), Map.of())
        .get(row.path("targetId").asLong());
      if (target == null) {
        row.putNull("targetId");
        row.put("missing", true);
      } else {
        row.put("targetId", target);
        row.remove("missing");
      }
    }
  }

  private boolean hasVolume(WritingBook b, String uid) {
    for (JsonNode v : parse(b.getVolumes()))
      if (v.path("uid").asText().equals(uid)) return true;
    return false;
  }

  public static String uuid(String text) {
    try {
      if (
        !UUID.fromString(text).toString().equals(text)
      ) throw new IllegalArgumentException();
      return text;
    } catch (Exception e) {
      throw bad("记录标识无效");
    }
  }

  public static String required(JsonNode node, String key, int max) {
    String value = node.path(key).asText("").trim();
    if (value.isBlank()) throw bad("请填写" + key);
    return limited(value, max);
  }

  private static String limited(String value, int max) {
    if (value.length() > max) throw bad("内容过长");
    return value;
  }

  public static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public static ResponseStatusException missing() {
    return new ResponseStatusException(
      HttpStatus.NOT_FOUND,
      "当前作品中找不到这条记录"
    );
  }

  public static ResponseStatusException conflict() {
    return new ResponseStatusException(
      HttpStatus.CONFLICT,
      "云端或其他窗口已有新版本，请先比较内容；本地草稿仍然保留"
    );
  }
}
