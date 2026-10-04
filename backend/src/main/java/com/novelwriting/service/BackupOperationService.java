package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.BackupOperation;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BackupOperationService {
  @PersistenceContext private EntityManager em;
  @Autowired private PlatformTransactionManager transactions;
  private final ThreadLocal<Long> currentOperation = new ThreadLocal<>();
  @FunctionalInterface public interface Work<T> { T run() throws Exception; }

  private <T> T independent(java.util.function.Supplier<T> work) {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(s -> work.get());
  }

  public long start(Long novelId, String type) {
    return independent(() -> {
      BackupOperation row = new BackupOperation();
      row.setNovelId(novelId); row.setType(type); row.setStatus("RUNNING");
      row.setStartedAt(Instant.now()); row.setStage(type); row.setMessage("操作进行中");
      em.persist(row); em.flush(); return row.getId();
    });
  }

  public void finish(long id, String status, String stage, String message, JsonNode details, Throwable failure) {
    independent(() -> {
      BackupOperation row = em.find(BackupOperation.class, id);
      row.setStatus(status); if (stage != null) row.setStage(stage); row.setFinishedAt(Instant.now());
      row.setMessage(message); row.setDetails(details == null ? null : stringify(summary(details)));
      if (failure != null) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        row.setErrorType(cause.getClass().getSimpleName());
        if (cause instanceof org.apache.http.client.HttpResponseException http) row.setHttpStatus(http.getStatusCode());
        if (failure instanceof ResponseStatusException response) row.setHttpStatus(response.getStatusCode().value());
      }
      return null;
    });
  }

  public void stage(String stage) {
    Long id = currentOperation.get();
    if (id != null) independent(() -> { em.find(BackupOperation.class, id).setStage(stage); return null; });
  }

  private static ObjectNode summary(JsonNode details) {
    ObjectNode safe = JSON.createObjectNode();
    for (String key : java.util.List.of("file", "filename", "revision", "bookUid", "copyNovelId", "size", "token", "requestId", "status", "operationId",
        "snapshotBytes", "snapshotCount", "legacyBytes", "legacyCount", "metadataBytes", "totalBytes", "unknownSizeCount", "protectedCount", "candidateBytes", "retainedBytes", "deletedBytes", "stage", "unlockWarning", "connectionWarning")) {
      JsonNode value = details.get(key);
      if (value != null && value.isValueNode()) safe.set(key, value);
    }
    for (String key : java.util.List.of("candidates", "retained", "chapters", "bridgeMarkers"))
      if (details.path(key).isArray()) safe.put(key + "Count", details.path(key).size());
    for (String key : java.util.List.of("deleted", "uncertainFiles"))
      if (details.path(key).isArray()) safe.set(key, details.path(key));
    return safe;
  }

  /** Exception strings from remote servers may contain credentials or URLs; never persist them. */
  public static String failureMessage(Throwable error) {
    if (error instanceof ResponseStatusException response && response.getReason() != null)
      return response.getReason();
    Throwable cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    if (cause instanceof org.apache.http.client.HttpResponseException http)
      return "远端服务器返回 HTTP " + http.getStatusCode() + "；请检查权限、连接或并发操作";
    if ("远端未提供可验证的目录排他锁和有限租期，清理已停止".equals(cause.getMessage())) return "远端未提供可验证的目录排他锁和有限租期，清理已停止";
    if (cause instanceof java.net.SocketTimeoutException) return "远端连接超时；请确认远端状态后重试";
    return "操作未完成；请检查连接、目录权限或备份内容（" + cause.getClass().getSimpleName() + "）";
  }

  public <T> T run(Long novelId, String type, Work<T> work) throws Exception {
    long id = start(novelId, type);
    Long previous = currentOperation.get(); currentOperation.set(id);
    try {
      T result = work.run();
      JsonNode details = null;
      if (result instanceof ObjectNode object) { object.put("operationId", id); details = object; }
      if (result instanceof Map<?, ?> map) details = JSON.valueToTree(map);
      boolean failed = details != null && details.has("success") && !details.path("success").asBoolean();
      finish(id, failed ? "FAILED" : details != null && "CANCELLED".equals(details.path("status").asText()) ? "CANCELLED" : "SUCCEEDED", "FINISHED",
        details != null && details.has("message") ? details.path("message").asText() : "操作完成", details, null);
      return result;
    } catch (Exception e) {
      finish(id, "FAILED", null, failureMessage(e), null, e);
      throw e;
    } finally { if (previous == null) currentOperation.remove(); else currentOperation.set(previous); }
  }

  public ObjectNode history(Long novelId, int limit, Long before) {
    return independent(() -> {
      int size = Math.min(100, Math.max(1, limit));
      var query = em.createQuery("from BackupOperation where novelId=:novel and id<:before order by id desc", BackupOperation.class)
        .setParameter("novel", novelId).setParameter("before", before == null ? Long.MAX_VALUE : before).setMaxResults(size + 1);
      var rows = query.getResultList(); ObjectNode out = JSON.createObjectNode(); var items = out.putArray("items");
      rows.stream().limit(size).forEach(r -> {
        ObjectNode item = items.addObject().put("id", r.getId()).put("type", r.getType()).put("status", r.getStatus())
          .put("startedAt", r.getStartedAt().toString()).put("finishedAt", r.getFinishedAt() == null ? null : r.getFinishedAt().toString())
          .put("stage", r.getStage()).put("message", r.getMessage()).put("errorType", r.getErrorType());
        if (r.getHttpStatus() == null) item.putNull("httpStatus"); else item.put("httpStatus", r.getHttpStatus());
        item.set("details", r.getDetails() == null ? JSON.nullNode() : parse(r.getDetails()));
      });
      if (rows.size() > size) out.put("nextBefore", rows.get(size - 1).getId()); else out.putNull("nextBefore");
      return out;
    });
  }
}
