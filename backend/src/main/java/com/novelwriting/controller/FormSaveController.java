package com.novelwriting.controller;

import com.novelwriting.entity.NovelOwned;
import com.novelwriting.service.FormSaveService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/novels/{novelId}/forms/{resource}")
@CrossOrigin(origins = "*")
public class FormSaveController {
    private final FormSaveService forms;
    public FormSaveController(FormSaveService forms) { this.forms = forms; }
    @PostMapping
    public NovelOwned create(@PathVariable Long novelId, @PathVariable String resource,
            @RequestHeader("Idempotency-Key") String token, @RequestBody FormSaveService.Form form) {
        return forms.save(novelId, resource, null, token, form);
    }
    @PutMapping("/{id}")
    public NovelOwned update(@PathVariable Long novelId, @PathVariable String resource, @PathVariable Long id,
            @RequestBody FormSaveService.Form form) {
        return forms.save(novelId, resource, id, null, form);
    }
}
