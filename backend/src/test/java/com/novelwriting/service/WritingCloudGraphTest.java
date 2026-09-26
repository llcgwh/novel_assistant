package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WritingCloudGraphTest {

  @Test
  void concurrentUploadsRemainTwoHeadsInsteadOfOverwritingEachOther() {
    String a = UUID.randomUUID().toString(),
      b = UUID.randomUUID().toString(),
      c = UUID.randomUUID().toString();
    var graph = WritingCloudService.graph(
      List.of(
        "20260926000001_" + a + "_root.ink.json",
        "20260926000002_" + b + "_" + a + ".ink.json",
        "20260926000003_" + c + "_" + a + ".ink.json"
      )
    );
    assertEquals(
      2,
      graph.stream().filter(WritingCloudService.Version::head).count()
    );
    assertTrue(
      graph.stream().noneMatch(v -> v.revision().equals(a) && v.head())
    );
  }

  @Test
  void unrelatedOrTraversingNamesCannotBecomeCloudVersions() {
    assertTrue(
      WritingCloudService.graph(
        List.of("../secret", "old.backup.json", "arbitrary.ink.json")
      ).isEmpty()
    );
  }

  @Test
  void explicitResolutionJoinsBranchesOnlyAfterItsSnapshotExists() {
    String a = UUID.randomUUID().toString(),
      b = UUID.randomUUID().toString(),
      c = UUID.randomUUID().toString();
    var names = new ArrayList<>(
      List.of(
        "20260926000001_" + a + "_root.ink.json",
        "20260926000002_" + b + "_root.ink.json",
        b + "_" + c + ".resolved"
      )
    );
    assertEquals(
      2,
      WritingCloudService.graph(names)
        .stream()
        .filter(WritingCloudService.Version::head)
        .count()
    );
    names.add("20260926000003_" + c + "_" + a + ".ink.json");
    assertEquals(
      1,
      WritingCloudService.graph(names)
        .stream()
        .filter(WritingCloudService.Version::head)
        .count()
    );
  }
}
