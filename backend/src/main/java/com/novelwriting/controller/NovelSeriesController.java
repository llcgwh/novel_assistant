package com.novelwriting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelwriting.service.SeriesProblem;
import com.novelwriting.service.SeriesService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/novels/{novelId}/series")
public class NovelSeriesController {
  private final SeriesService series;
  public NovelSeriesController(SeriesService series) { this.series = series; }

  @GetMapping
  public JsonNode get(@PathVariable Long novelId) { return series.get(novelId); }
  @PostMapping("/worlds")
  public JsonNode createWorld(@PathVariable Long novelId, @RequestBody JsonNode body) { return series.world(novelId, null, "create", body); }
  @PutMapping("/worlds/{uid}")
  public JsonNode editWorld(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.world(novelId, uid, "edit", body); }
  @PostMapping("/worlds/{uid}/remove")
  public JsonNode removeWorld(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.world(novelId, uid, "remove", body); }
  @PostMapping("/copies")
  public JsonNode createCopy(@PathVariable Long novelId, @RequestBody JsonNode body) { return series.createCopy(novelId, body); }
  @PostMapping("/copies/{uid}/duplicate")
  public JsonNode duplicate(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.duplicateCopy(novelId, uid, body); }
  @PutMapping("/copies/{uid}")
  public JsonNode edit(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.editCopy(novelId, uid, body); }
  @PostMapping("/copies/{uid}/archive")
  public JsonNode archive(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.archiveCopy(novelId, uid, body); }
  @PostMapping("/copies/{uid}/restore")
  public JsonNode restore(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.restoreCopy(novelId, uid, body); }
  @PostMapping("/copies/{uid}/compare")
  public JsonNode compare(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.compare(novelId, uid, body); }
  @PostMapping("/copies/{uid}/adopt")
  public JsonNode adopt(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.adopt(novelId, uid, body, false); }
  @PostMapping("/copies/{uid}/review")
  public JsonNode review(@PathVariable Long novelId, @PathVariable String uid, @RequestBody JsonNode body) { return series.adopt(novelId, uid, body, true); }

  @ExceptionHandler(SeriesProblem.class)
  public ResponseEntity<?> problem(SeriesProblem problem) {
    return ResponseEntity.status(problem.getStatusCode()).body(Map.of("code", problem.getCode(), "message", problem.getReason()));
  }
}
