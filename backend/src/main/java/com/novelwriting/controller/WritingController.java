package com.novelwriting.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelwriting.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController @RequestMapping("/api/novels/{novelId}/writing")
public class WritingController {
 @Autowired private WritingService service;
 @Autowired private ManuscriptExportService exporter;
 @GetMapping public JsonNode workspace(@PathVariable Long novelId) { return service.workspace(novelId); }
 @GetMapping("/chapters/{uid}") public JsonNode get(@PathVariable Long novelId,@PathVariable String uid) { return service.get(novelId,uid); }
 @PostMapping("/chapters") public JsonNode create(@PathVariable Long novelId,@RequestBody JsonNode body) { return service.create(novelId,body); }
 @PutMapping("/chapters/{uid}") public JsonNode save(@PathVariable Long novelId,@PathVariable String uid,@RequestBody JsonNode body) { return service.save(novelId,uid,body); }
 @PutMapping("/structure") public JsonNode structure(@PathVariable Long novelId,@RequestBody JsonNode body) { return service.structure(novelId,body); }
 @PutMapping("/preferences") public JsonNode preferences(@PathVariable Long novelId,@RequestBody JsonNode body) { return service.preferences(novelId,body); }
 @PostMapping("/chapters/{uid}/split") public JsonNode split(@PathVariable Long novelId,@PathVariable String uid,@RequestBody JsonNode body) { return service.split(novelId,uid,body); }
 @PostMapping("/chapters/{uid}/merge") public JsonNode merge(@PathVariable Long novelId,@PathVariable String uid,@RequestBody JsonNode body) { return service.merge(novelId,uid,body); }
 @GetMapping("/chapters/{uid}/revisions") public JsonNode revisions(@PathVariable Long novelId,@PathVariable String uid) { return service.revisions(novelId,uid); }
 @GetMapping("/chapters/{uid}/revisions/{revisionId}") public JsonNode revision(@PathVariable Long novelId,@PathVariable String uid,@PathVariable Long revisionId) { return service.revision(novelId,uid,revisionId); }
 @PutMapping("/sessions/{uid}") public Map<String,Boolean> session(@PathVariable Long novelId,@PathVariable String uid,@RequestBody JsonNode body) { service.session(novelId,uid,body); return Map.of("saved",true); }
 @GetMapping("/search") public JsonNode search(@PathVariable Long novelId,@RequestParam String q) { return service.search(novelId,q); }
 @GetMapping("/backlinks") public JsonNode backlinks(@PathVariable Long novelId,@RequestParam String type,@RequestParam Long target) { return service.backlinks(novelId,type,target); }
 @PostMapping("/export") public ResponseEntity<byte[]> export(@PathVariable Long novelId,@RequestBody JsonNode body) throws Exception {
    String format=body.path("format").asText("txt"); byte[] bytes=exporter.export(novelId,body);
    String contentType=format.equals("docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":format.equals("md")?"text/markdown;charset=UTF-8":"text/plain;charset=UTF-8";
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"manuscript."+format+"\"").contentType(MediaType.parseMediaType(contentType)).body(bytes);
 }
 @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"操作失败":e.getReason())); }
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
}
