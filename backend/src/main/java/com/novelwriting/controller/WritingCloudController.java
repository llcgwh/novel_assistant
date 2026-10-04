package com.novelwriting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelwriting.service.WritingCloudService;
import com.novelwriting.service.SeriesProblem;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/novels/{novelId}/writing/cloud")
public class WritingCloudController {

  private final WritingCloudService cloud;

  public WritingCloudController(WritingCloudService cloud) {
    this.cloud = cloud;
  }

  @GetMapping("/versions")
  public JsonNode versions(
    @PathVariable Long novelId,
    @RequestParam(required = false) String book
  ) throws Exception {
    return cloud.versions(novelId, book);
  }

  @PostMapping("/preview")
  public JsonNode preview(
    @PathVariable Long novelId,
    @RequestBody JsonNode body
  ) throws Exception {
    return cloud.preview(
      novelId,
      body.path("book").asText(),
      body.path("file").asText()
    );
  }

  @PostMapping("/push")
  public JsonNode push(@PathVariable Long novelId) throws Exception {
    return cloud.push(novelId);
  }

  @PostMapping("/pull")
  public JsonNode pull(@PathVariable Long novelId, @RequestBody JsonNode body)
    throws Exception {
    return cloud.pull(
      novelId,
      body.path("book").asText(),
      body.path("file").asText()
    );
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    if (e instanceof SeriesProblem series) return ResponseEntity.status(series.getStatusCode()).body(
      Map.of("code", series.getCode(), "message", series.getReason())
    );
    return ResponseEntity.status(e.getStatusCode()).body(
      Map.of("message", e.getReason() == null ? "同步未完成" : e.getReason())
    );
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> failed(Exception e) {
    return ResponseEntity.status(502).body(
      Map.of(
        "message",
        "WebDAV 操作失败，请检查连接、目录权限或备份内容；本机稿件仍保留"
      )
    );
  }
}
