package com.novelwriting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelwriting.service.SeriesProblem;
import com.novelwriting.service.SeriesService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/series")
public class SeriesController {
  private final SeriesService series;
  public SeriesController(SeriesService series) { this.series = series; }

  @GetMapping("/templates")
  public JsonNode templates(@RequestParam(defaultValue = "false") boolean includeArchived) { return series.templates(includeArchived); }
  @GetMapping("/templates/{uid}")
  public JsonNode template(@PathVariable String uid) { return series.template(uid); }
  @PostMapping("/templates")
  public JsonNode create(@RequestBody JsonNode body) { return series.createTemplate(body); }
  @PostMapping("/templates/{uid}/revisions")
  public JsonNode publish(@PathVariable String uid, @RequestBody JsonNode body) { return series.publish(uid, body); }
  @PostMapping("/templates/{uid}/head")
  public JsonNode head(@PathVariable String uid, @RequestBody JsonNode body) { return series.selectHead(uid, body); }
  @PostMapping("/templates/{uid}/archive")
  public JsonNode archive(@PathVariable String uid, @RequestBody JsonNode body) { return series.archiveTemplate(uid, body); }
  @PostMapping("/export")
  public JsonNode export(@RequestBody(required = false) JsonNode body) { return series.exportLibrary(body); }
  @PostMapping("/import/preview")
  public JsonNode preview(@RequestBody JsonNode body) { return series.importPreview(body); }
  @PostMapping("/import")
  public JsonNode importLibrary(@RequestBody JsonNode body) { return series.importLibrary(body); }

  @ExceptionHandler(SeriesProblem.class)
  public ResponseEntity<?> problem(SeriesProblem problem) {
    return ResponseEntity.status(problem.getStatusCode()).body(Map.of("code", problem.getCode(), "message", problem.getReason()));
  }
}
