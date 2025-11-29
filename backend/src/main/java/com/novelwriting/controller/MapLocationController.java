package com.novelwriting.controller;

import com.novelwriting.entity.MapLocation;
import com.novelwriting.service.MapLocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/map-locations")
@CrossOrigin(origins = "*")
public class MapLocationController {

    @Autowired
    private MapLocationService mapLocationService;

    @GetMapping
    public List<MapLocation> getAllMapLocations() {
        return mapLocationService.getAllMapLocations();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MapLocation> getMapLocationById(@PathVariable Long id) {
        return mapLocationService.getMapLocationById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public MapLocation createMapLocation(@RequestBody MapLocation mapLocation) {
        return mapLocationService.createMapLocation(mapLocation);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MapLocation> updateMapLocation(@PathVariable Long id, @RequestBody MapLocation mapLocation) {
        try {
            return ResponseEntity.ok(mapLocationService.updateMapLocation(id, mapLocation));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMapLocation(@PathVariable Long id) {
        mapLocationService.deleteMapLocation(id);
        return ResponseEntity.ok().build();
    }
}
