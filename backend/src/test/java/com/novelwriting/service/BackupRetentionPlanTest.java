package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;
import static com.novelwriting.service.WritingDocuments.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BackupRetentionPlanTest {
  String uid(int n) { return String.format("00000000-0000-4000-8000-%012d", n); }
  String file(int n, int parent) { return "20200101" + String.format("%06d", n) + "_" + uid(n) + "_" + (parent == 0 ? "root" : uid(parent)) + ".ink.json"; }
  BackupMaintenanceService.Entry entry(String name) { return new BackupMaintenanceService.Entry(name, 100, "\"v1\"", "2020-01-01T00:00:00Z", false); }
  List<BackupMaintenanceService.Entry> chain() { List<BackupMaintenanceService.Entry> rows = new ArrayList<>(); for (int i = 1; i <= 8; i++) rows.add(entry(file(i, i - 1))); return rows; }
  com.fasterxml.jackson.databind.node.ObjectNode plan(List<BackupMaintenanceService.Entry> rows, Set<String> local) {
    return BackupMaintenanceService.plan(rows, local, 2, 30, Instant.parse("2026-10-04T00:00:00Z"));
  }
  Set<String> heads(List<String> names) { Set<String> out = new HashSet<>(); WritingCloudService.graph(names).stream().filter(WritingCloudService.Version::head).forEach(v -> out.add(v.revision())); return out; }
  @Test void keepsRootsHeadsNewestAndLocalRecoveryBases() {
    var result = plan(chain(), Set.of(uid(4))); Set<String> kept = new HashSet<>(); result.path("retained").forEach(r -> kept.add(r.path("revision").asText()));
    assertEquals(Set.of(uid(1), uid(4), uid(7), uid(8)), kept);
    assertEquals(4, result.path("candidates").size()); assertEquals(400, result.path("candidateBytes").asLong());
  }
  @Test void bridgeMarkersPreserveHeadsAfterEveryPartialDeletion() {
    var entries = chain(); var result = plan(entries, Set.of()); List<String> names = new ArrayList<>(entries.stream().map(BackupMaintenanceService.Entry::file).toList());
    Set<String> expected = heads(names); result.path("bridgeMarkers").forEach(r -> names.add(r.asText())); assertEquals(expected, heads(names));
    for (var candidate : result.path("candidates")) { names.remove(candidate.path("file").asText()); assertEquals(expected, heads(names)); }
  }
  @Test void resolvedBranchTargetCanBePrunedWithoutRevivingSource() {
    var entries = chain(); entries.add(entry(file(9, 1))); entries.add(entry(uid(9) + "_" + uid(3) + ".resolved"));
    var result = plan(entries, Set.of()); List<String> names = new ArrayList<>(entries.stream().map(BackupMaintenanceService.Entry::file).toList());
    Set<String> expected = heads(names); assertEquals(Set.of(uid(8)), expected);
    result.path("bridgeMarkers").forEach(r -> names.add(r.asText()));
    for (var candidate : result.path("candidates")) { names.remove(candidate.path("file").asText()); assertEquals(expected, heads(names)); }
  }
  @Test void retainsEveryParallelHeadAndUnknownMetadata() {
    var entries = chain(); entries.add(entry(file(9, 3))); entries.add(entry("manual-notes.json"));
    var result = plan(entries, Set.of()); Set<String> kept = new HashSet<>(); result.path("retained").forEach(r -> kept.add(r.path("file").asText()));
    assertTrue(kept.contains(file(8, 7))); assertTrue(kept.contains(file(9, 3))); assertTrue(kept.contains("manual-notes.json"));
  }
  @Test void noWeakWildcardOrMissingEtagCanAuthorizeDeletion() {
    for (String etag : Arrays.asList(null, "", "*", "\"*\"", "unquoted", "W/\"weak\"", "\"one\",\"two\"")) {
      var entries = chain(); entries.set(2, new BackupMaintenanceService.Entry(file(3, 2), 100, etag, "", false));
      assertFalse(plan(entries, Set.of()).path("candidates").toString().contains(file(3, 2)));
    }
  }
  @Test void recentVersionsAreProtectedAndNothingIsAutomaticallyDeleted() {
    var entries = chain(); var result = BackupMaintenanceService.plan(entries, Set.of(), 2, 36500, Instant.now());
    assertEquals(0, result.path("candidates").size()); assertEquals(0, result.path("bridgeMarkers").size());
  }
  @Test void deviceFilenameTimezoneCannotExpireAStillRecentRemoteSnapshot() {
    var entries = chain();
    String candidate = "20261003203000_" + uid(3) + "_" + uid(2) + ".ink.json";
    entries.set(2, new BackupMaintenanceService.Entry(candidate, 100, "\"v1\"", "2026-10-04T00:30:00Z", false));
    entries.set(6, new BackupMaintenanceService.Entry("20261004235000_" + uid(7) + "_" + uid(6) + ".ink.json", 100, "\"v1\"", "2026-10-04T23:50:00Z", false));
    entries.set(7, new BackupMaintenanceService.Entry("20261004235900_" + uid(8) + "_" + uid(7) + ".ink.json", 100, "\"v1\"", "2026-10-04T23:59:00Z", false));
    var result = BackupMaintenanceService.plan(entries, Set.of(), 2, 1, Instant.parse("2026-10-05T00:00:00Z"));
    assertFalse(result.path("candidates").toString().contains(candidate));
    var kept = new ArrayList<String>(); result.path("retained").forEach(r -> { if (candidate.equals(r.path("file").asText())) kept.add(r.path("time").asText()); });
    assertEquals(List.of("2026-10-04T00:30:00Z"), kept);
  }
  @Test void newestCountUsesRemoteInstantsAndUnknownTimesRemainProtected() {
    var entries = chain();
    entries.set(2, new BackupMaintenanceService.Entry(file(3, 2), 100, "\"v1\"", "2020-03-01T00:00:00Z", false));
    entries.set(7, new BackupMaintenanceService.Entry(file(8, 7), 100, "\"v1\"", "2020-04-01T00:00:00Z", false));
    entries.set(3, new BackupMaintenanceService.Entry(file(4, 3), 100, "\"v1\"", "unknown", false));
    var result = plan(entries, Set.of());
    assertFalse(result.path("candidates").toString().contains(file(3, 2)));
    assertFalse(result.path("candidates").toString().contains(file(4, 3)));
    assertTrue(result.path("candidates").toString().contains(file(7, 6)));
  }
  @Test void duplicateRevisionFailsClosed() {
    var entries = chain(); entries.add(entry("20200102000003_" + uid(3) + "_" + uid(2) + ".ink.json"));
    assertThrows(ResponseStatusException.class, () -> plan(entries, Set.of()));
  }
  @Test void graphWithoutRecoveryHeadFailsClosed() {
    var entries = List.of(entry(file(1, 2)), entry(file(2, 1)));
    assertThrows(ResponseStatusException.class, () -> plan(entries, Set.of()));
  }
}
