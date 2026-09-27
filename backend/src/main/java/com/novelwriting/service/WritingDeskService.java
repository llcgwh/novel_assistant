package com.novelwriting.service;

import static com.novelwriting.service.WritingDeskDocuments.*;
import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.WritingBook;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class WritingDeskService {

  @Autowired
  private WritingService writing;

  @PersistenceContext
  private EntityManager em;

  public ObjectNode get(Long novelId) {
    return read(writing.book(novelId));
  }

  public ObjectNode save(Long novelId, JsonNode input) {
    WritingBook book = writing.book(novelId);
    ObjectNode current = read(book);
    if (
      input != null &&
      input.isObject() &&
      !input.has("rounds") &&
      hasRounds(current)
    ) {
      throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "此客户端未包含修订轮次。请刷新后重试，避免清除现有轮次及任务分组。"
      );
    }
    ObjectNode next = validateDesk(input, true);
    long version = current.path("version").asLong();
    String mutation = next.path("mutationId").asText();
    if (mutation.equals(current.path("mutationId").asText())) {
      if (
        next.path("version").asLong() == version - 1 &&
        content(next).equals(content(current))
      ) return current;
      throw WritingService.conflict();
    }
    if (
      next.path("version").asLong() != version
    ) throw WritingService.conflict();
    validateOwnership(novelId, current, next);
    replace(book, next);
    next.put("mutationId", mutation);
    book.setDeskData(stringify(next));
    writing.touch(book, false);
    return next;
  }

  private void validateOwnership(Long novelId, JsonNode old, JsonNode next) {
    Set<String> owned = new HashSet<>(
      em
        .createQuery(
          "select uid from WritingChapter where novelId=:id",
          String.class
        )
        .setParameter("id", novelId)
        .getResultList()
    );
    checkAnchor(next.path("nextPen"), old.path("nextPen"), owned);
    for (String key : List.of("tasks", "bookmarks")) {
      Map<String, JsonNode> previous = new HashMap<>();
      old.path(key).forEach(row -> previous.put(row.path("uid").asText(), row));
      for (JsonNode row : next.path(key))
        checkAnchor(row, previous.get(row.path("uid").asText()), owned);
    }
  }

  private void checkAnchor(
    JsonNode anchor,
    JsonNode previous,
    Set<String> owned
  ) {
    if (!anchor.isObject()) return;
    String chapter = anchor.path("chapterUid").asText();
    if (
      previous != null && chapter.equals(previous.path("chapterUid").asText())
    ) return;
    if (!owned.contains(chapter)) throw WritingService.bad(
      "创作工作台引用的章节不属于当前作品"
    );
  }
}
