package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, WritingService.class, WritingDeskService.class,
  BackupBundleService.class, ExportService.class, ImportService.class, SeriesService.class})
class StoryStateTest {
  static final Path ROOT = temp();
  static Path temp() { try { return Files.createTempDirectory("story-state-test-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("app.upload.dir", ROOT::toString); }
  @AfterAll static void cleanup() throws Exception { try (var paths = Files.walk(ROOT)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
  @Autowired EntityManager em;
  @Autowired WritingService writing;
  @Autowired WritingDeskService desk;
  @Autowired BackupBundleService bundles;

  long novel() { Novel n = new Novel(); n.setTitle("状态测试作品"); em.persist(n); em.flush(); return n.getId(); }
  Character person(long id) { Character p = new Character(); p.setNovelId(id); p.setName("阿青"); p.setPersonality("谨慎"); em.persist(p); em.flush(); return p; }
  TimelineEvent event(long id) { TimelineEvent e = new TimelineEvent(); e.setNovelId(id); e.setTitle("渡河"); em.persist(e); em.flush(); return e; }
  String chapter(long id) {
    ObjectNode c = JSON.createObjectNode().put("uid", UUID.randomUUID().toString()).put("title", "第一章");
    c.set("doc", parse("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"出发\"}]},{\"type\":\"paragraph\",\"attrs\":{\"id\":\"p2\"},\"content\":[{\"type\":\"text\",\"text\":\"渡河\"}]}]}"));
    return writing.create(id, c).path("uid").asText();
  }
  ObjectNode source() { return JSON.createObjectNode().put("chapterUid", "").put("blockId", "").put("excerpt", "").putNull("eventId").put("eventTitle", "").put("note", "作者确认：第二日渡河"); }
  ObjectNode base() { ObjectNode r = JSON.createObjectNode().put("uid", UUID.randomUUID().toString()).put("storyOrder", 20).put("narrativeOrder", 3).put("recordedAt", "2026-10-16T10:00:00Z").put("retracted", false); r.set("source", source()); return r; }
  ObjectNode state(Character p) { return base().put("characterId", p.getId()).put("characterName", p.getName()).put("field", "所在地").put("value", "河对岸"); }
  ObjectNode secret(Character p) {
    ObjectNode secret = JSON.createObjectNode().put("uid", UUID.randomUUID().toString()).put("title", "信件作者").put("truth", "信是姐姐写的").put("recordedAt", "2026-10-16T10:00:00Z").put("archived", false);
    secret.putArray("knowledge").add(base().put("characterId", p.getId()).put("characterName", p.getName()).put("state", "believe").put("belief", "他相信是父亲写的"));
    secret.putArray("reader").add(base().put("state", "revealed").put("detail", "读者看到姐姐写信，阿青尚不知情"));
    return secret;
  }
  ObjectNode mutation(JsonNode input) { return ((ObjectNode)input.deepCopy()).put("mutationId", UUID.randomUUID().toString()); }
  ObjectNode populated(long id, Character p) {
    ObjectNode input = mutation(desk.get(id));
    ((ObjectNode)input.path("storyState")).withArray("states").add(state(p));
    ((ObjectNode)input.path("storyState")).withArray("secrets").add(secret(p));
    return input;
  }
  void bad(Runnable action) { ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run); assertEquals(400, e.getStatusCode().value()); }
  void conflict(Runnable action) { ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run); assertEquals(409, e.getStatusCode().value()); }

  @Test void recordsAreDurableIndependentFromPortraitAndKnowledgeNeverRevealsToCharacters() {
    long id = novel(); Character p = person(id); ObjectNode input = populated(id, p);
    long sequence = writing.workspace(id).path("changeSequence").asLong();
    ObjectNode saved = desk.save(id, input); em.flush(); em.clear();
    assertEquals(saved, desk.get(id)); assertEquals(sequence + 1, writing.workspace(id).path("changeSequence").asLong());
    assertEquals("谨慎", em.find(Character.class, p.getId()).getPersonality());
    JsonNode ledger = saved.path("storyState");
    assertEquals(20, ledger.path("states").get(0).path("storyOrder").asInt());
    assertEquals(3, ledger.path("states").get(0).path("narrativeOrder").asInt());
    assertEquals("believe", ledger.path("secrets").get(0).path("knowledge").get(0).path("state").asText());
    assertEquals("revealed", ledger.path("secrets").get(0).path("reader").get(0).path("state").asText());
  }
  @Test void retriesAreIdempotentAndStaleClientsCannotOverwriteOrEraseLedger() {
    long id = novel(); ObjectNode input = populated(id, person(id)); ObjectNode saved = desk.save(id, input);
    assertEquals(saved, desk.save(id, input));
    conflict(() -> desk.save(id, mutation(input)));
    ObjectNode oldClient = mutation(saved); oldClient.remove("storyState"); conflict(() -> desk.save(id, oldClient));
    assertEquals(saved, desk.get(id));
  }
  @Test void historyCanBeRetractedAndExtendedButNeverOverwrittenOrDropped() {
    long id = novel(); Character p = person(id); ObjectNode saved = desk.save(id, populated(id, p));
    ObjectNode changed = mutation(saved); ((ObjectNode)changed.path("storyState").path("states").get(0)).put("value", "新身份"); bad(() -> desk.save(id, changed));
    ObjectNode deleted = mutation(saved); ((ArrayNode)deleted.path("storyState").path("states")).removeAll(); bad(() -> desk.save(id, deleted));
    ObjectNode correction = mutation(saved); ((ObjectNode)correction.path("storyState").path("states").get(0)).put("retracted", true);
    ((ArrayNode)correction.path("storyState").path("states")).add(state(p).put("value", "渡口").put("storyOrder", 21));
    ObjectNode result = desk.save(id, correction);
    assertEquals(2, result.path("storyState").path("states").size()); assertTrue(result.path("storyState").path("states").get(0).path("retracted").asBoolean());
  }
  @Test void foreignPeopleChaptersEventsAndInventedBlocksAreRejected() {
    long id = novel(), other = novel(); Character p = person(id), foreign = person(other); String ownChapter = chapter(id), foreignChapter = chapter(other); TimelineEvent foreignEvent = event(other);
    for (String kind : List.of("person", "chapter", "event", "block")) {
      ObjectNode input = populated(id, p); ObjectNode row = (ObjectNode)input.path("storyState").path("states").get(0); ObjectNode source = (ObjectNode)row.path("source");
      switch (kind) {
        case "person" -> row.put("characterId", foreign.getId());
        case "chapter" -> source.put("chapterUid", foreignChapter);
        case "event" -> source.put("eventId", foreignEvent.getId()).put("eventTitle", foreignEvent.getTitle());
        case "block" -> source.put("chapterUid", ownChapter).put("blockId", "invented");
      }
      bad(() -> desk.save(id, input));
    }
    assertFalse(StoryStateDocuments.hasData(desk.get(other)));
  }
  @Test void deletedResourcesLeaveReadableHistoricalEvidence() {
    long id = novel(); Character p = person(id); ObjectNode saved = desk.save(id, populated(id, p));
    em.remove(p); em.flush();
    ObjectNode changed = mutation(saved); ((ObjectNode)changed.path("storyState").path("states").get(0)).put("retracted", true);
    assertEquals("阿青", desk.save(id, changed).path("storyState").path("states").get(0).path("characterName").asText());
  }
  @Test void strictSchemaRejectsInvalidStatesOrdersUnknownFieldsAndReusedIds() {
    long id = novel(); ObjectNode good = populated(id, person(id));
    for (String fault : List.of("state", "order", "field", "duplicate", "version")) {
      ObjectNode input = good.deepCopy(); ObjectNode story = (ObjectNode)input.path("storyState");
      switch (fault) {
        case "state" -> ((ObjectNode)story.path("secrets").get(0).path("knowledge").get(0)).put("state", "revealed");
        case "order" -> ((ObjectNode)story.path("states").get(0)).put("storyOrder", -1);
        case "field" -> ((ObjectNode)story.path("states").get(0)).put("automaticInference", true);
        case "duplicate" -> ((ObjectNode)story.path("secrets").get(0).path("knowledge").get(0)).put("uid", story.path("states").get(0).path("uid").asText());
        case "version" -> story.put("schemaVersion", 2);
      }
      bad(() -> WritingDeskDocuments.validateDesk(input, true));
    }
    ObjectNode undated = good.deepCopy(); ((ObjectNode)undated.path("storyState").path("states").get(0)).putNull("storyOrder").putNull("narrativeOrder");
    assertDoesNotThrow(() -> WritingDeskDocuments.validateDesk(undated, true));
  }
  @Test void fullBackupAndSharedCloudBundleRoundTripRemapsPersonAndEventReferences() throws Exception {
    long id = novel(), target = novel(); Character p = person(id); TimelineEvent event = event(id); String chapter = chapter(id);
    ObjectNode input = populated(id, p); ObjectNode src = (ObjectNode)input.path("storyState").path("states").get(0).path("source");
    src.put("chapterUid", chapter).put("blockId", "p2").put("excerpt", "渡河").put("eventId", event.getId()).put("eventTitle", event.getTitle());
    desk.save(id, input); em.flush();
    byte[] bundle = bundles.exportBundle(id);
    assertTrue(new String(bundle, java.nio.charset.StandardCharsets.UTF_8).contains("storyState"));
    bundles.restore(target, bundle); em.flush(); em.clear();
    JsonNode restored = desk.get(target).path("storyState"), row = restored.path("states").get(0);
    assertNotEquals(p.getId().longValue(), row.path("characterId").asLong());
    assertEquals(target, em.find(Character.class, row.path("characterId").asLong()).getNovelId());
    assertEquals(target, em.find(TimelineEvent.class, row.path("source").path("eventId").asLong()).getNovelId());
    assertEquals(chapter, row.path("source").path("chapterUid").asText());
    assertEquals("p2", row.path("source").path("blockId").asText());
    assertEquals(row.path("characterId"), restored.path("secrets").get(0).path("knowledge").get(0).path("characterId"));
    bundles.validateRecoveryBundle(parse(new String(bundles.exportBundle(target), java.nio.charset.StandardCharsets.UTF_8)));
  }
  @Test void legacyBackupsCannotSilentlyEraseExistingState() {
    long id = novel(); desk.save(id, populated(id, person(id)));
    ObjectNode legacy = writing.exportBackup(id); ((ObjectNode)legacy.path("desk")).remove("storyState");
    ObjectNode root = JSON.createObjectNode(); root.set("writing", legacy);
    bad(() -> writing.protectLegacyRestore(id, root));
    long empty = novel(); assertDoesNotThrow(() -> writing.protectLegacyRestore(empty, root));
  }
  @Test void paragraphEvidenceFollowsSplitAndMergeWithoutReorderingStory() {
    long id = novel(); String first = chapter(id); ObjectNode input = populated(id, person(id));
    ObjectNode src = (ObjectNode)input.path("storyState").path("states").get(0).path("source"); src.put("chapterUid", first).put("blockId", "p2").put("excerpt", "渡河");
    desk.save(id, input); String second = UUID.randomUUID().toString();
    writing.split(id, first, JSON.createObjectNode().put("revision", 0).put("index", 1).put("uid", second).put("title", "后一章"));
    JsonNode split = desk.get(id).path("storyState").path("states").get(0);
    assertEquals(second, split.path("source").path("chapterUid").asText()); assertEquals(20, split.path("storyOrder").asInt()); assertEquals(3, split.path("narrativeOrder").asInt());
    writing.merge(id, first, JSON.createObjectNode().put("revision", 1).put("otherRevision", 0).put("otherUid", second));
    assertEquals(first, desk.get(id).path("storyState").path("states").get(0).path("source").path("chapterUid").asText());
  }
}
