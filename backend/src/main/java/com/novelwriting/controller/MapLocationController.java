package com.novelwriting.controller;

import com.novelwriting.entity.MapLocation;
import com.novelwriting.service.MapLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/novels/{novelId}/map-locations")
@CrossOrigin(origins = "*")
public class MapLocationController {

    @Autowired
    private MapLocationService mapLocationService;

    @GetMapping
    public List<MapLocation> getMapLocationsByNovelId(@PathVariable Long novelId) {
        return mapLocationService.getMapLocationsByNovelId(novelId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MapLocation> getMapLocationById(@PathVariable Long novelId, @PathVariable Long id) {
        return mapLocationService.getMapLocationById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public MapLocation createMapLocation(@PathVariable Long novelId, @RequestBody MapLocation location) {
        location.setNovelId(novelId);
        return mapLocationService.createMapLocation(location);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MapLocation> updateMapLocation(@PathVariable Long novelId, @PathVariable Long id, @RequestBody MapLocation location) {
        try {
            return ResponseEntity.ok(mapLocationService.updateMapLocation(id, location));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMapLocation(@PathVariable Long novelId, @PathVariable Long id) {
        mapLocationService.deleteMapLocation(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/search")
    public List<MapLocation> searchMapLocations(@PathVariable Long novelId, @RequestParam String keyword) {
        return mapLocationService.searchMapLocations(novelId, keyword);
    }

    @GetMapping("/type/{locationType}")
    public List<MapLocation> getMapLocationsByType(@PathVariable Long novelId, @PathVariable String locationType) {
        return mapLocationService.getMapLocationsByType(novelId, locationType);
    }

    @PostMapping("/{id}/tags/{tagId}")
    public ResponseEntity<MapLocation> addTagToMapLocation(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(mapLocationService.addTagToMapLocation(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}/tags/{tagId}")
    public ResponseEntity<MapLocation> removeTagFromMapLocation(@PathVariable Long novelId, @PathVariable Long id, @PathVariable Long tagId) {
        try {
            return ResponseEntity.ok(mapLocationService.removeTagFromMapLocation(id, tagId));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/tags")
    public ResponseEntity<MapLocation> setMapLocationTags(@PathVariable Long novelId, @PathVariable Long id, @RequestBody Set<Long> tagIds) {
        try {
            return ResponseEntity.ok(mapLocationService.setMapLocationTags(id, tagIds));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
