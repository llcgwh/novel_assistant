package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.novelwriting.entity.WritingBook;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class WritingStatsDocumentsTest {

  WritingBook book() {
    WritingBook book = new WritingBook();
    book.setUid(UUID.randomUUID().toString());
    return book;
  }

  ObjectNode focus(String uid) {
    ObjectNode receipt = JSON.createObjectNode().put("uid", uid)
      .put("endedOn", LocalDate.now(ZoneOffset.UTC).toString())
      .put("completed", false).put("timezoneOffsetMinutes", 0);
    receipt.putObject("secondsByDate");
    return receipt;
  }

  @Test
  void javascriptTimezoneOffsetDeterminesCurrentLocalDayExactly() {
    for (int offset : new int[] { -840, -480, 0, 330, 840 }) {
      LocalDate local = Instant.now().minusSeconds(offset * 60L)
        .atOffset(ZoneOffset.UTC).toLocalDate();
      WritingBook book = book();
      ObjectNode input = JSON.createObjectNode().put("date", local.toString())
        .put("timezoneOffsetMinutes", offset);
      assertTrue(WritingStatsDocuments.captureDay(book, input, false));
      assertEquals(local.toString(), WritingStatsDocuments.read(book).path("days").get(0).path("date").asText());
      assertEquals(2000, WritingStatsDocuments.read(book).path("days").get(0).path("goal").asInt());
    }
  }

  @Test
  void receiptLimitRejectsNewRecordsWithoutMutatingStoredStatsButAllowsRetries() {
    WritingBook book = book();
    ObjectNode stats = WritingStatsDocuments.emptyStats();
    String existing = UUID.randomUUID().toString();
    ArrayNode receipts = stats.withArray("focusReceipts");
    ObjectNode first = focus(existing);
    first.remove("timezoneOffsetMinutes");
    receipts.add(first);
    for (int i = 1; i < WritingStatsDocuments.MAX_RECEIPTS; i++) {
      ObjectNode receipt = focus(UUID.randomUUID().toString());
      receipt.remove("timezoneOffsetMinutes");
      receipts.add(receipt);
    }
    book.setStatsData(stringify(stats));
    String prior = book.getStatsData();
    assertFalse(WritingStatsDocuments.focus(book, existing, focus(existing)));
    assertEquals(prior, book.getStatsData());
    String fresh = UUID.randomUUID().toString();
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
      () -> WritingStatsDocuments.focus(book, fresh, focus(fresh)));
    assertEquals(400, error.getStatusCode().value());
    assertTrue(error.getReason().contains("20000"));
    assertEquals(prior, book.getStatsData());
  }

  @Test
  void oversizedDocumentsAndExcessDailyRowsAreRejectedExplicitly() {
    ObjectNode oversized = WritingStatsDocuments.emptyStats();
    oversized.put("unexpected", "x".repeat(WritingStatsDocuments.MAX_BYTES));
    assertTrue(assertThrows(ResponseStatusException.class,
      () -> WritingStatsDocuments.validateStats(oversized)).getReason().contains("8 MiB"));

    ObjectNode tooMany = WritingStatsDocuments.emptyStats();
    ArrayNode days = tooMany.withArray("days");
    for (int i = 0; i <= WritingStatsDocuments.MAX_DAYS; i++) days.addObject();
    assertThrows(ResponseStatusException.class,
      () -> WritingStatsDocuments.validateStats(tooMany));
  }

  @Test
  void zeroDurationSlicesAreCanonicalAcrossStorageAndBackup() {
    WritingBook book = book();
    String uid = UUID.randomUUID().toString();
    ObjectNode input = focus(uid);
    input.withObject("secondsByDate").put(LocalDate.now(ZoneOffset.UTC).toString(), 0);
    assertTrue(WritingStatsDocuments.focus(book, uid, input));
    assertFalse(WritingStatsDocuments.focus(book, uid, focus(uid)));
    ObjectNode backup = WritingStatsDocuments.read(book);
    assertTrue(backup.path("focusReceipts").get(0).path("secondsByDate").isEmpty());
    WritingStatsDocuments.replace(book, backup);
    assertFalse(WritingStatsDocuments.focus(book, uid, input));
  }
}
