package com.novelwriting.service;

import static com.novelwriting.service.SeriesDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, WritingService.class, SeriesService.class})
class SeriesServiceTest {
  @Autowired EntityManager em;
  @Autowired SeriesService series;

  long novel() { Novel n = new Novel(); n.setTitle("系列领域验证"); em.persist(n); em.flush(); return n.getId(); }
  String uid() { return UUID.randomUUID().toString(); }
  ObjectNode payload(String kind, String name) {
    ObjectNode out = JSON.createObjectNode();
    for (String field : fields(kind)) out.put(field, field.equals("name") ? name : "");
    return out.put("description", "保留中文、空白和段落。\n第二行。  ");
  }
  ObjectNode createSource(String kind) {
    ObjectNode input = JSON.createObjectNode().put("mutationId", uid()).put("templateUid", uid()).put("kind", kind)
      .put("seriesName", "群星世界").put("authorStatus", "confirmed").put("changeNote", "第一版");
    input.set("payload", payload(kind, "共同设定"));
    return series.createTemplate(input);
  }
  ObjectNode mutation(JsonNode state) {
    return JSON.createObjectNode().put("mutationId", uid()).put("epoch", state.path("epoch").asText())
      .put("expectedVersion", state.path("version").asLong());
  }
  ObjectNode copyRequest(long novel, JsonNode source) {
    ObjectNode state = series.get(novel);
    return mutation(state).put("copyUid", uid()).put("templateUid", source.path("template").path("uid").asText())
      .put("revisionUid", source.path("revision").path("uid").asText())
      .put("universeUid", state.path("worlds").get(0).path("uid").asText()).put("planet", "青星");
  }
  ObjectNode copy(long novel, JsonNode source) { return series.createCopy(novel, copyRequest(novel, source)); }
  ObjectNode firstCopy(JsonNode state) { return (ObjectNode) state.path("copies").get(0); }
  ObjectNode publication(JsonNode source, String name, String description) {
    String template = source.path("template").path("uid").asText();
    ObjectNode current = series.template(template);
    ObjectNode input = JSON.createObjectNode().put("mutationId", uid())
      .put("expectedLockVersion", current.path("template").path("lockVersion").asLong())
      .put("expectedHeadRevisionUid", current.path("template").path("headRevisionUid").asText())
      .put("seriesName", "群星世界").put("authorStatus", "draft").put("changeNote", "新版本");
    ObjectNode content = (ObjectNode) source.path("revision").path("payload").deepCopy();
    content.put("name", name).put("description", description); input.set("payload", content);
    return input;
  }
  ObjectNode preview(long novel, String copyUid, String revision) {
    return series.compare(novel, copyUid, JSON.createObjectNode().put("revisionUid", revision));
  }
  ObjectNode adoption(JsonNode comparison, String... selected) {
    ObjectNode request = JSON.createObjectNode().put("mutationId", uid()).put("epoch", comparison.path("epoch").asText())
      .put("expectedVersion", comparison.path("expectedVersion").asLong()).put("copyHash", comparison.path("copyHash").asText())
      .put("baselineRevisionUid", comparison.path("baselineRevisionUid").asText()).put("revisionUid", comparison.path("revision").path("uid").asText());
    ArrayNode fields = request.putArray("selectedFields"); for (String field : selected) fields.add(field);
    return request;
  }
  JsonNode difference(JsonNode compare, String key) {
    for (JsonNode row : compare.path("fields")) if (key.equals(row.path("key").asText())) return row;
    throw new AssertionError("Missing difference");
  }

  @Test void completeFiveKindsRemainIndependentAcrossNovelsAndWorlds() {
    long a = novel(), b = novel();
    for (String kind : List.of("calendar", "location", "race", "organization", "character")) {
      ObjectNode source = createSource(kind);
      copy(a, source); copy(b, source);
    }
    ObjectNode beforeB = series.get(b), stateA = series.get(a);
    ObjectNode world = mutation(stateA).put("worldUid", uid()).put("name", "平行世界").put("description", "局部设定独立");
    stateA = (ObjectNode) series.world(a, null, "create", world).path("state");
    String original = firstCopy(stateA).path("uid").asText();
    ObjectNode duplicate = mutation(stateA).put("copyUid", uid()).put("universeUid", world.path("worldUid").asText()).put("planet", "另一颗星球");
    stateA = (ObjectNode) series.duplicateCopy(a, original, duplicate).path("state");
    ObjectNode local = firstCopy(stateA);
    ObjectNode edit = mutation(stateA).put("authorStatus", "draft").put("planet", "青星");
    edit.set("content", local.path("content").deepCopy()); ((ObjectNode) edit.path("content")).put("name", "仅本作改名");
    stateA = (ObjectNode) series.editCopy(a, original, edit).path("state");
    assertEquals(beforeB, series.get(b));
    assertEquals("共同设定", stateA.path("copies").get(5).path("content").path("name").asText());
    assertEquals(6, stateA.path("copies").size());
    assertTrue(stateA.path("copies").get(0).path("content").path("description").asText().endsWith("  "));
    assertEquals(0L, em.createQuery("select count(c) from WritingChapter c", Long.class).getSingleResult(), "本作系列设定不创建正文");
  }

  @Test void partialAdoptionPreservesLocalChangesAndMovesTheCompleteBaseline() {
    long id = novel(); ObjectNode source = createSource("character");
    ObjectNode state = (ObjectNode) copy(id, source).path("state");
    String copyUid = firstCopy(state).path("uid").asText(), templateUid = source.path("template").path("uid").asText();
    ObjectNode edit = mutation(state).put("authorStatus", "confirmed").put("planet", "青星");
    edit.set("content", firstCopy(state).path("content").deepCopy()); ((ObjectNode) edit.path("content")).put("name", "本作姓名");
    series.editCopy(id, copyUid, edit);
    ObjectNode v2 = series.publish(templateUid, publication(source, "新版母本姓名", "新版正文"));
    ObjectNode compare = preview(id, copyUid, v2.path("revision").path("uid").asText());
    assertTrue(difference(compare, "name").path("conflict").asBoolean());
    assertFalse(difference(compare, "description").path("conflict").asBoolean());
    ObjectNode apply = adoption(compare, "description");
    ObjectNode v3 = series.publish(templateUid, publication(v2, "第三版母本姓名", "第三版正文"));
    state = (ObjectNode) series.adopt(id, copyUid, apply, false).path("state");
    JsonNode local = firstCopy(state);
    assertEquals("本作姓名", local.path("content").path("name").asText());
    assertEquals("新版正文", local.path("content").path("description").asText());
    assertEquals("draft", local.path("authorStatus").asText());
    assertEquals(v2.path("revision").path("uid"), local.path("baselineRevisionUid"));
    assertEquals("local", local.path("fieldOrigins").path("name").path("kind").asText());
    assertEquals(1, local.path("lastReview").path("adoptedFields").size());
    JsonNode later = preview(id, copyUid, v3.path("revision").path("uid").asText());
    assertEquals("新版母本姓名", difference(later, "name").path("base").asText());
    assertEquals("本作姓名", difference(later, "name").path("local").asText());
    assertEquals(v3.path("revision").path("uid"), series.template(templateUid).path("template").path("headRevisionUid"));
  }

  @Test void copyReplayReturnsCurrentStateEvenAfterSourceArchiveAndLocalEdit() {
    long id = novel(); ObjectNode source = createSource("location");
    ObjectNode input = copyRequest(id, source), result = series.createCopy(id, input);
    ObjectNode state = (ObjectNode) result.path("state"); String localUid = firstCopy(state).path("uid").asText();
    ObjectNode edit = mutation(state).put("planet", "另星").put("authorStatus", "draft");
    edit.set("content", firstCopy(state).path("content").deepCopy()); ((ObjectNode) edit.path("content")).put("name", "本作地名");
    series.editCopy(id, localUid, edit);
    series.archiveTemplate(source.path("template").path("uid").asText(), JSON.createObjectNode().put("mutationId", uid())
      .put("expectedLockVersion", source.path("template").path("lockVersion").asLong()).put("archived", true));
    ObjectNode replay = series.createCopy(id, input);
    assertTrue(replay.path("replayed").asBoolean());
    assertEquals(result.path("resultVersion"), replay.path("resultVersion"));
    assertEquals("本作地名", firstCopy(replay.path("state")).path("content").path("name").asText());
    assertEquals(1, replay.path("state").path("copies").size());
    ObjectNode altered = input.deepCopy().put("planet", "假装新请求");
    assertEquals("mutation_reused", assertThrows(SeriesProblem.class, () -> series.createCopy(id, altered)).getCode());
  }

  @Test void explicitReviewAndHistoryRestoreKeepThePreviousState() {
    long id = novel(); ObjectNode source = createSource("calendar");
    ObjectNode state = (ObjectNode) copy(id, source).path("state"); String localUid = firstCopy(state).path("uid").asText();
    ObjectNode v2 = series.publish(source.path("template").path("uid").asText(), publication(source, "新历", "新的历法"));
    ObjectNode comparison = preview(id, localUid, v2.path("revision").path("uid").asText());
    ObjectNode review = adoption(comparison); review.remove("selectedFields");
    state = (ObjectNode) series.adopt(id, localUid, review, true).path("state");
    ObjectNode copy = firstCopy(state);
    assertEquals("共同设定", copy.path("content").path("name").asText());
    assertEquals(v2.path("revision").path("uid"), copy.path("baselineRevisionUid"));
    String historical = copy.path("history").get(0).path("uid").asText();
    long version = state.path("version").asLong();
    state = (ObjectNode) series.restoreCopy(id, localUid, mutation(state).put("historyUid", historical)).path("state");
    assertEquals(version + 1, state.path("version").asLong());
    assertEquals(2, firstCopy(state).path("history").size());
    assertEquals(source.path("revision").path("uid"), firstCopy(state).path("baselineRevisionUid"));
    assertEquals(v2.path("revision").path("uid"), firstCopy(state).path("history").get(1).path("before").path("baselineRevisionUid"));
  }

  @Test void restoreChangesEpochBeforeReceiptLookup() {
    long id = novel(); ObjectNode source = createSource("race");
    ObjectNode request = copyRequest(id, source); series.createCopy(id, request);
    ObjectNode backup = series.exportNovel(id);
    String beforeEpoch = series.get(id).path("epoch").asText();
    series.restoreNovel(id, backup);
    assertNotEquals(beforeEpoch, series.get(id).path("epoch").asText());
    assertEquals("epoch_conflict", assertThrows(SeriesProblem.class, () -> series.createCopy(id, request)).getCode());
  }

  @Test void branchImportPreservesHeadAndRetriesBeforeCheckingOldPlan() {
    ObjectNode source = createSource("organization"); String templateUid = source.path("template").path("uid").asText();
    ObjectNode v2 = series.publish(templateUid, publication(source, "新组织", "新版规则"));
    ObjectNode pack = series.exportLibrary(JSON.createObjectNode());
    ObjectNode branch = ((ObjectNode) v2.path("revision")).deepCopy().put("uid", uid()).put("changeNote", "另一设备分支");
    ((ObjectNode) branch.path("payload")).put("description", "分支规则"); branch.put("hash", revisionHash(branch));
    pack.withArray("revisions").add(branch);
    ((ObjectNode) pack.path("templates").get(0)).put("headRevisionUid", branch.path("uid").asText());
    ObjectNode wrapper = JSON.createObjectNode(); wrapper.set("package", pack);
    ObjectNode plan = series.importPreview(wrapper);
    ObjectNode input = wrapper.deepCopy().put("mutationId", uid()).put("planToken", plan.path("planToken").asText());
    ObjectNode result = series.importLibrary(input);
    assertEquals(1, result.path("addedRevisions").asInt());
    assertTrue(series.importLibrary(input).path("replayed").asBoolean());
    JsonNode actual = series.template(templateUid);
    assertEquals(v2.path("revision").path("uid"), actual.path("template").path("headRevisionUid"));
    assertEquals(3, actual.path("revisions").size());
    assertEquals(2, actual.path("revisions").get(2).path("number").asInt());
  }

  @Test void sameRevisionIdentityWithRehashedDifferentContentIsRejected() {
    createSource("calendar"); ObjectNode pack = series.exportLibrary(JSON.createObjectNode());
    ObjectNode revision = (ObjectNode) pack.path("revisions").get(0);
    revision.put("changeNote", "重算hash仍不得改身份").put("hash", ""); revision.put("hash", revisionHash(revision));
    ObjectNode input = JSON.createObjectNode(); input.set("package", pack);
    assertEquals("source_identity", assertThrows(SeriesProblem.class, () -> series.importPreview(input)).getCode());
  }

  @Test void twoPublishersCannotOverwriteTheSameHeadAndSuccessfulRetrySurvivesLaterPublish() {
    ObjectNode source = createSource("character"); String template = source.path("template").path("uid").asText();
    ObjectNode first = publication(source, "第一修改", "内容"), stale = publication(source, "并行修改", "内容");
    ObjectNode saved = series.publish(template, first);
    assertEquals("version_conflict", assertThrows(SeriesProblem.class, () -> series.publish(template, stale)).getCode());
    series.publish(template, publication(saved, "再次修改", "内容"));
    ObjectNode replay = series.publish(template, first);
    assertTrue(replay.path("replayed").asBoolean());
    assertEquals(saved.path("revision"), replay.path("revision"));
    assertEquals(3, replay.path("template").path("revisionCount").asInt());
  }

  @Test void staleComparisonCannotMutateAndWorldsWithArchivedCopiesCannotBeRemoved() {
    long id = novel(); ObjectNode source = createSource("location");
    ObjectNode state = (ObjectNode) copy(id, source).path("state"); String localUid = firstCopy(state).path("uid").asText();
    ObjectNode comparison = preview(id, localUid, source.path("revision").path("uid").asText());
    ObjectNode stale = adoption(comparison, "name");
    state = (ObjectNode) series.archiveCopy(id, localUid, mutation(state).put("archived", true)).path("state");
    assertEquals("version_conflict", assertThrows(SeriesProblem.class, () -> series.adopt(id, localUid, stale, false)).getCode());
    ObjectNode add = mutation(state).put("worldUid", uid()).put("name", "新世界").put("description", "");
    state = (ObjectNode) series.world(id, null, "create", add).path("state");
    ObjectNode removal = mutation(state);
    String usedWorld = firstCopy(state).path("universeUid").asText();
    assertEquals("world_in_use", assertThrows(SeriesProblem.class, () -> series.world(id, usedWorld, "remove", removal)).getCode());
  }

  @Test void unicodeNameWhitespaceIsNormalizedBeforeRevisionHashing() {
    ObjectNode input = JSON.createObjectNode().put("mutationId", uid()).put("templateUid", uid()).put("kind", "calendar")
      .put("seriesName", "\u2003星海\u2003").put("authorStatus", "draft").put("changeNote", "");
    input.set("payload", payload("calendar", "\u2003历法\u2003"));
    ObjectNode created = series.createTemplate(input);
    assertEquals("星海", created.path("revision").path("seriesName").asText());
    assertEquals("历法", created.path("revision").path("payload").path("name").asText());
    assertEquals(created.path("revision"), validateRevision(created.path("revision")));
  }

  @Test void editingCannotSilentlyMoveACopyIntoAnotherWorld() {
    long id = novel(); ObjectNode source = createSource("race");
    ObjectNode state = (ObjectNode) copy(id, source).path("state");
    ObjectNode before = state.deepCopy(), first = firstCopy(state);
    ObjectNode edit = mutation(state).put("authorStatus", "draft").put("planet", "").put("universeUid", uid());
    edit.set("content", first.path("content").deepCopy());
    assertEquals("invalid_series", assertThrows(SeriesProblem.class,
      () -> series.editCopy(id, first.path("uid").asText(), edit)).getCode());
    assertEquals(before, series.get(id));
  }

  @Test void anImportCannotMakeTheCompleteLibraryImpossibleToExport() {
    ObjectNode source = createSource("character");
    String templateUid = source.path("template").path("uid").asText();
    ObjectNode pack = series.exportLibrary(JSON.createObjectNode());
    String text = "x".repeat(20_000);
    for (int i = 0; i < 132; i++) {
      ObjectNode revision = ((ObjectNode) source.path("revision")).deepCopy().put("uid", uid()).put("number", i + 2L)
        .put("parentRevisionUid", source.path("revision").path("uid").asText());
      ObjectNode content = (ObjectNode) revision.path("payload");
      for (String field : fields("character")) if (!field.equals("name")) content.put(field, text);
      revision.put("hash", revisionHash(revision)); pack.withArray("revisions").add(revision);
    }
    ObjectNode request = JSON.createObjectNode(); request.set("package", pack);
    SeriesProblem problem = assertThrows(SeriesProblem.class, () -> series.importPreview(request));
    assertEquals(413, problem.getStatusCode().value());
    assertEquals("series_capacity", problem.getCode());
    assertEquals(1, series.template(templateUid).path("revisions").size());
    assertTrue(stringify(series.exportLibrary(JSON.createObjectNode())).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MAX_PACK_BYTES);
  }
}
