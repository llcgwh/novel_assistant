package com.novelwriting.service;

import static com.novelwriting.service.SeriesDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeriesDocumentsTest {
  private static final String TIME = "2026-10-04T12:00:00Z";
  private static String uid() { return UUID.randomUUID().toString(); }

  private ObjectNode payload(String kind) {
    ObjectNode result = JSON.createObjectNode();
    for (String key : fields(kind)) result.put(key, key.equals("name") ? "星海设定" : "");
    return result;
  }

  private ObjectNode revision(String kind, String template, String parent) {
    ObjectNode result = JSON.createObjectNode().put("uid", uid()).put("templateUid", template)
      .put("number", parent == null ? 1L : 2L).put("kind", kind).put("seriesName", "星海系列")
      .put("authorStatus", "draft").put("changeNote", "初始设定").put("createdAt", TIME);
    if (parent == null) result.putNull("parentRevisionUid"); else result.put("parentRevisionUid", parent);
    result.set("payload", payload(kind));
    return sign(result);
  }

  private ObjectNode sign(ObjectNode revision) {
    revision.put("hash", revisionHash(revision));
    return revision;
  }

  private ObjectNode pack(ObjectNode... revisions) {
    ObjectNode result = JSON.createObjectNode().put("format", "novel-assistant-series-v1")
      .put("schemaVersion", 1).put("exportedAt", TIME);
    ArrayNode templates = result.putArray("templates"), rows = result.putArray("revisions");
    Set<String> seen = new HashSet<>();
    for (ObjectNode revision : revisions) {
      String template = revision.path("templateUid").asText();
      if (seen.add(template)) templates.addObject().put("uid", template).put("kind", revision.path("kind").asText())
        .put("headRevisionUid", revision.path("uid").asText()).put("archived", false);
      rows.add(revision.deepCopy());
    }
    return result;
  }

  private ObjectNode state(ObjectNode source, ObjectNode... ancestors) {
    ObjectNode state = emptyState();
    String kind = source.path("kind").asText(), revision = source.path("uid").asText();
    ArrayNode sources = (ArrayNode) state.get("sourceRevisions");
    for (ObjectNode ancestor : ancestors) sources.add(ancestor.deepCopy());
    sources.add(source.deepCopy());
    ObjectNode copy = state.withArray("copies").addObject().put("uid", uid()).put("kind", kind)
      .put("universeUid", state.path("worlds").get(0).path("uid").asText()).put("planet", "蓝星")
      .put("authorStatus", "draft").put("baselineRevisionUid", revision).put("archived", false)
      .put("createdAt", TIME).put("updatedAt", TIME);
    copy.set("content", source.path("payload").deepCopy());
    copy.putObject("origin").put("templateUid", source.path("templateUid").asText())
      .put("revisionUid", revision).put("copiedAt", TIME);
    ObjectNode review = copy.putObject("lastReview").put("revisionUid", revision).put("mode", "copy").put("reviewedAt", TIME);
    ArrayNode adopted = review.putArray("adoptedFields");
    review.putArray("keptFields");
    ObjectNode origins = copy.putObject("fieldOrigins");
    for (String field : fields(kind)) {
      adopted.add(field);
      origins.putObject(field).put("kind", "source").put("revisionUid", revision);
    }
    copy.putArray("history");
    return state;
  }

  private ObjectNode copy(ObjectNode state) { return (ObjectNode) state.path("copies").get(0); }

  private ObjectNode before(ObjectNode copy) {
    ObjectNode result = copy.deepCopy();
    result.remove(List.of("uid", "kind", "origin", "createdAt", "updatedAt", "history"));
    return result;
  }

  private ObjectNode backup(ObjectNode state, ObjectNode pack) {
    ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1);
    ObjectNode document = state.deepCopy(); document.remove("epoch");
    result.set("document", document); result.set("library", pack);
    return result;
  }

  @ParameterizedTest
  @ValueSource(strings = {"calendar", "location", "race", "organization", "character"})
  void everyKindRoundTripsWithCompleteEditableTextAndProvenance(String kind) throws Exception {
    ObjectNode input = payload(kind);
    input.put("name", "  有名字的设定  ").put("description", "  中文第一行\n第二行\n  ");
    ObjectNode normalized = validatePayload(kind, input);
    assertEquals("有名字的设定", normalized.path("name").asText());
    assertEquals("  中文第一行\n第二行\n  ", normalized.path("description").asText());
    assertEquals("  有名字的设定  ", input.path("name").asText(), "validation must not mutate caller input");
    ObjectNode source = revision(kind, uid(), null); source.set("payload", normalized); sign(source);
    ObjectNode state = state(source);
    ObjectNode validated = validateState(JSON.readTree(stringify(state)), true);
    assertEquals(normalized, validated.path("copies").get(0).path("content"));
    assertEquals(source, validated.path("sourceRevisions").get(0));
    assertDoesNotThrow(() -> validateBackup(backup(state, pack(source))));
  }

  @Test void payloadRejectsMissingNullNonTextAndUnknownFieldsWithoutStripping() {
    for (String mutation : List.of("missing", "null", "number", "unknown", "image", "blank")) {
      ObjectNode input = payload("character");
      switch (mutation) {
        case "missing" -> input.remove("appearance");
        case "null" -> input.putNull("notes");
        case "number" -> input.put("role", 7);
        case "unknown" -> input.put("@class", "example.Untrusted");
        case "image" -> input.put("portraitImage", "/api/novels/7/images/9/file");
        case "blank" -> input.put("name", "   ");
      }
      assertThrows(SeriesProblem.class, () -> validatePayload("character", input), mutation);
    }
    assertThrows(SeriesProblem.class, () -> fields("other"));
  }

  @Test void hashesSortObjectKeysPreserveArrayOrderAndCoverImmutableIdentity() throws Exception {
    JsonNode a = JSON.readTree("{\"z\":[1,2],\"a\":{\"y\":2,\"x\":1}}");
    JsonNode b = JSON.readTree("{\"a\":{\"x\":1,\"y\":2},\"z\":[1,2]}");
    assertEquals(hash(a), hash(b));
    assertNotEquals(hash(a), hash(JSON.readTree("{\"z\":[2,1],\"a\":{\"y\":2,\"x\":1}}")));
    ObjectNode revision = revision("calendar", uid(), null);
    String digest = revisionHash(revision);
    revision.put("hash", "ignored");
    assertEquals(digest, revisionHash(revision));
    revision.put("createdAt", "2026-10-04T12:00:01Z");
    assertNotEquals(digest, revisionHash(revision));
  }

  @Test void portableHashGoldenVectorPreservesEmojiAndPinsJacksonControlEscapes() throws Exception {
    ObjectNode input = JSON.createObjectNode();
    input.put("z", "星🌙\n\t\r\b\f" + (char) 0 + (char) 0x1f + "\"\\/");
    input.putArray("a").add("😀").add("中文");
    // This is the byte protocol, not writeValueAsString: Jackson's UTF-8 writer
    // escapes supplementary code points as uppercase UTF-16 surrogate pairs.
    String encoded = "{\"a\":[\"\\uD83D\\uDE00\",\"中文\"],\"z\":\"星\\uD83C\\uDF19\\n\\t\\r\\b\\f\\u0000\\u001F\\\"\\\\/\"}";
    String expectedHash = "8dcc5bbe380de5983423eb0bb4783bccf6c2c110267586fd816f919f8e3c962a";
    ObjectNode sorted = JSON.createObjectNode();
    sorted.set("a", input.path("a")); sorted.set("z", input.path("z"));
    assertEquals(encoded, new String(JSON.writeValueAsBytes(sorted), StandardCharsets.UTF_8));
    assertEquals(expectedHash, hash(input));
    assertEquals(expectedHash, revisionHash(input.deepCopy().put("hash", "ignored")));
    assertEquals(input, JSON.readTree(encoded), "the hashing byte representation must retain the exact authored text");
  }

  @Test void revisionRequiresCorrectHashAndCanonicalIdentityAndUtcTimestamp() {
    for (String mutation : List.of("hash", "payload", "uid", "time", "extra")) {
      ObjectNode source = revision("race", uid(), null);
      switch (mutation) {
        case "hash" -> source.put("hash", "0".repeat(64));
        case "payload" -> ((ObjectNode) source.get("payload")).put("notes", "篡改");
        case "uid" -> { source.put("uid", "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB"); sign(source); }
        case "time" -> { source.put("createdAt", "2026-10-04T12:00:00+08:00"); sign(source); }
        case "extra" -> { source.put("novelId", 42); sign(source); }
      }
      assertThrows(SeriesProblem.class, () -> validateRevision(source), mutation);
    }
  }

  @Test void importAcceptsConcurrentDisplayNumbersWithoutChangingIdentity() {
    String template = uid();
    ObjectNode root = revision("location", template, null);
    ObjectNode branchA = revision("location", template, root.path("uid").asText());
    ObjectNode branchB = revision("location", template, root.path("uid").asText());
    ObjectNode result = validatePack(pack(root, branchA, branchB));
    assertEquals(3, result.path("revisions").size());
    assertEquals(root.path("uid"), result.path("templates").get(0).path("headRevisionUid"));
  }

  @Test void importRejectsDuplicateRecordsMissingParentsCyclesAndCrossTemplateParents() {
    String template = uid();
    ObjectNode root = revision("calendar", template, null);
    ObjectNode child = revision("calendar", template, root.path("uid").asText());
    assertThrows(SeriesProblem.class, () -> validatePack(pack(root, root)));
    ObjectNode duplicateTemplate = pack(root);
    duplicateTemplate.withArray("templates").add(duplicateTemplate.path("templates").get(0).deepCopy());
    assertThrows(SeriesProblem.class, () -> validatePack(duplicateTemplate));
    assertThrows(SeriesProblem.class, () -> validatePack(pack(child)));
    ObjectNode cycleRoot = root.deepCopy().put("parentRevisionUid", child.path("uid").asText()); sign(cycleRoot);
    assertThrows(SeriesProblem.class, () -> validatePack(pack(cycleRoot, child)));
    ObjectNode foreign = revision("calendar", uid(), root.path("uid").asText());
    assertThrows(SeriesProblem.class, () -> validatePack(pack(root, foreign)));
    ObjectNode wrongKind = revision("race", template, root.path("uid").asText());
    assertThrows(SeriesProblem.class, () -> validatePack(pack(root, wrongKind)));
  }

  @Test void longRevisionChainUsesBoundedIterativeGraphTraversal() {
    String template = uid(), parent = null;
    ObjectNode[] revisions = new ObjectNode[2_000];
    for (int i = 0; i < revisions.length; i++) {
      revisions[i] = revision("calendar", template, parent);
      parent = revisions[i].path("uid").asText();
    }
    assertEquals(revisions.length, validatePack(pack(revisions)).path("revisions").size());
  }

  @Test void stateRequiresWorldsEpochSafeCountersAndNoInventedOwnershipFields() {
    ObjectNode empty = emptyState();
    assertDoesNotThrow(() -> validateState(empty, true));
    assertThrows(SeriesProblem.class, () -> validateState(empty, false));
    ObjectNode portable = empty.deepCopy(); portable.remove("epoch");
    assertDoesNotThrow(() -> validateState(portable, false));
    assertThrows(SeriesProblem.class, () -> validateState(portable, true));
    ObjectNode noWorlds = empty.deepCopy(); noWorlds.withArray("worlds").removeAll();
    assertThrows(SeriesProblem.class, () -> validateState(noWorlds, true));
    ObjectNode badCounter = empty.deepCopy().put("version", new BigInteger("9007199254740992"));
    assertThrows(SeriesProblem.class, () -> validateState(badCounter, true));
    ObjectNode fractional = empty.deepCopy().put("version", 1.5);
    assertThrows(SeriesProblem.class, () -> validateState(fractional, true));
    ObjectNode owned = empty.deepCopy().put("novelId", 1);
    assertThrows(SeriesProblem.class, () -> validateState(owned, true));
  }

  @Test void stateRequiresCompleteSourceClosureAndRejectsForeignOrUnusedSources() {
    ObjectNode root = revision("character", uid(), null);
    ObjectNode child = revision("character", root.path("templateUid").asText(), root.path("uid").asText());
    assertDoesNotThrow(() -> validateState(state(child, root), true));
    assertThrows(SeriesProblem.class, () -> validateState(state(child), true));
    ObjectNode foreign = revision("character", uid(), null);
    ObjectNode invalid = state(root);
    invalid.withArray("sourceRevisions").add(foreign);
    assertThrows(SeriesProblem.class, () -> validateState(invalid, true));
    ((ObjectNode) copy(invalid).get("origin")).put("templateUid", foreign.path("templateUid").asText());
    assertThrows(SeriesProblem.class, () -> validateState(invalid, true));
    ObjectNode noWorld = state(root); copy(noWorld).put("universeUid", uid());
    assertThrows(SeriesProblem.class, () -> validateState(noWorld, true));
  }

  @Test void fieldOriginsCannotInventSourceAttributionAndReviewsPartitionAllFields() {
    ObjectNode root = revision("organization", uid(), null);
    ObjectNode edited = state(root);
    ((ObjectNode) copy(edited).get("content")).put("notes", "本作修改");
    assertThrows(SeriesProblem.class, () -> validateState(edited, true));
    ((ObjectNode) copy(edited).path("fieldOrigins").get("notes")).put("kind", "local").putNull("revisionUid");
    assertDoesNotThrow(() -> validateState(edited, true));
    ((ObjectNode) copy(edited).get("lastReview")).withArray("keptFields").add("notes");
    assertThrows(SeriesProblem.class, () -> validateState(edited, true), "a field cannot be both adopted and kept");
    ObjectNode missing = state(root);
    ((ArrayNode) copy(missing).path("lastReview").get("adoptedFields")).remove(0);
    assertThrows(SeriesProblem.class, () -> validateState(missing, true));
    ObjectNode emptyAdopt = state(root);
    ObjectNode review = (ObjectNode) copy(emptyAdopt).get("lastReview"); review.put("mode", "adopt");
    review.set("keptFields", review.get("adoptedFields").deepCopy()); review.withArray("adoptedFields").removeAll();
    assertThrows(SeriesProblem.class, () -> validateState(emptyAdopt, true));
  }

  @Test void historyIsFlatAndRetainsItsOwnSourceClosure() {
    ObjectNode root = revision("race", uid(), null), state = state(root), copy = copy(state);
    ObjectNode snapshot = before(copy);
    copy.withArray("history").addObject().put("uid", uid()).put("createdAt", TIME).put("action", "edit")
      .set("before", snapshot);
    ((ObjectNode) copy.get("content")).put("notes", "本地新内容");
    ((ObjectNode) copy.path("fieldOrigins").get("notes")).put("kind", "local").putNull("revisionUid");
    assertDoesNotThrow(() -> validateState(state, true));
    snapshot.putArray("history");
    assertThrows(SeriesProblem.class, () -> validateState(state, true), "recursive history is forbidden");
  }

  @Test void backupRequiresIdenticalExactPinnedLibraryAndForbidsEpochOrOtherNovelCopies() {
    ObjectNode root = revision("location", uid(), null), state = state(root);
    ObjectNode valid = backup(state, pack(root));
    assertDoesNotThrow(() -> validateBackup(valid));
    ObjectNode different = valid.deepCopy();
    ObjectNode changed = (ObjectNode) different.path("library").path("revisions").get(0);
    ((ObjectNode) changed.get("payload")).put("notes", "同UUID不同内容"); sign(changed);
    assertThrows(SeriesProblem.class, () -> validateBackup(different));
    ObjectNode unused = revision("location", root.path("templateUid").asText(), root.path("uid").asText());
    assertThrows(SeriesProblem.class, () -> validateBackup(backup(state, pack(root, unused))));
    ObjectNode badEpoch = valid.deepCopy(); ((ObjectNode) badEpoch.get("document")).put("epoch", uid());
    assertThrows(SeriesProblem.class, () -> validateBackup(badEpoch));
    ObjectNode ownership = valid.deepCopy(); ((ObjectNode) ownership.get("library")).putArray("copies");
    assertThrows(SeriesProblem.class, () -> validateBackup(ownership));
  }

  @Test void backupSubsetHeadMustBeOneOfItsPinnedBaselinesOrOrigins() {
    ObjectNode ancestor = revision("location", uid(), null);
    ObjectNode pinned = revision("location", ancestor.path("templateUid").asText(), ancestor.path("uid").asText());
    ObjectNode state = state(pinned, ancestor), library = pack(ancestor, pinned);
    assertThrows(SeriesProblem.class, () -> validateBackup(backup(state, library)));
    ((ObjectNode) library.path("templates").get(0)).put("headRevisionUid", pinned.path("uid").asText());
    assertDoesNotThrow(() -> validateBackup(backup(state, library)));
  }

  @Test void emptyDetectionProtectsWorldChangesCopiesSourcesAndMalformedSavedState() {
    assertFalse(hasData(null));
    ObjectNode state = emptyState();
    assertFalse(hasData(state));
    assertNotEquals(state.path("epoch"), emptyState().path("epoch"));
    ObjectNode renamed = state.deepCopy(); ((ObjectNode) renamed.path("worlds").get(0)).put("name", "平行世界");
    assertTrue(hasData(renamed));
    ObjectNode described = state.deepCopy(); ((ObjectNode) described.path("worlds").get(0)).put("description", "作者设定");
    assertTrue(hasData(described));
    assertTrue(hasData(state(revision("calendar", uid(), null))));
    assertTrue(hasData(JSON.createObjectNode()));
    ObjectNode malformed = emptyState(); ((ObjectNode) malformed.path("worlds").get(0)).remove("uid");
    assertTrue(hasData(malformed), "an invalid empty-looking document must not be erased by an old backup");
    ObjectNode sources = state.deepCopy(); sources.withArray("sourceRevisions").add(revision("race", uid(), null));
    assertTrue(hasData(sources));
  }

  @Test void capacitiesCountSerializedBytesAndProtectCollectionsAndOversizedInput() {
    ObjectNode payload = payload("character");
    for (String field : fields("character")) if (!field.equals("name")) payload.put(field, "\u0001".repeat(20_000));
    assertThrows(SeriesProblem.class, () -> validatePayload("character", payload));
    ObjectNode worlds = emptyState();
    for (int i = 0; i < 100; i++) worlds.withArray("worlds").addObject().put("uid", uid()).put("name", "世界").put("description", "");
    assertThrows(SeriesProblem.class, () -> validateState(worlds, true));
    ObjectNode copies = state(revision("calendar", uid(), null));
    ObjectNode original = copy(copies).deepCopy();
    for (int i = 0; i < 1_000; i++) copies.withArray("copies").add(original.deepCopy().put("uid", uid()));
    assertThrows(SeriesProblem.class, () -> validateState(copies, true));
    ObjectNode largeState = emptyState().put("oversized", "x".repeat(MAX_STATE_BYTES));
    SeriesProblem stateFailure = assertThrows(SeriesProblem.class, () -> validateState(largeState, true));
    assertEquals(413, stateFailure.getStatusCode().value());
    assertTrue(stateFailure.getMessage().contains("容量"));
    ObjectNode largePack = pack().put("oversized", "x".repeat(MAX_PACK_BYTES));
    SeriesProblem packFailure = assertThrows(SeriesProblem.class, () -> validatePack(largePack));
    assertTrue(packFailure.getMessage().contains("容量"));
  }

  @Test void deeplyNestedUnknownDataIsRejectedBeforeRecursiveCopyOrHashing() {
    ObjectNode root = JSON.createObjectNode(), cursor = root;
    for (int i = 0; i < 2_000; i++) cursor = cursor.putObject("nested");
    assertThrows(SeriesProblem.class, () -> validateState(root, true));
    assertThrows(SeriesProblem.class, () -> hash(root));
  }

  @Test void parserRejectsDuplicateJsonKeysBeforeTheyCanBeSilentlyReplaced() {
    assertThrows(Exception.class, () -> JSON.readTree("{\"name\":\"第一份\",\"name\":\"第二份\"}"));
  }
}
