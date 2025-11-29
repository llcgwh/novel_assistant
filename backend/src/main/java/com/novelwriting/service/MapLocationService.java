package com.novelwriting.service;

import com.novelwriting.entity.MapLocation;
import com.novelwriting.repository.MapLocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class MapLocationService {

    @Autowired
    private MapLocationRepository mapLocationRepository;

    public List<MapLocation> getAllMapLocations() {
        return mapLocationRepository.findAll();
    }

    public Optional<MapLocation> getMapLocationById(Long id) {
        return mapLocationRepository.findById(id);
    }

    public MapLocation createMapLocation(MapLocation mapLocation) {
        return mapLocationRepository.save(mapLocation);
    }

    public MapLocation updateMapLocation(Long id, MapLocation locationDetails) {
        MapLocation location = mapLocationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));

        location.setName(locationDetails.getName());
        location.setDescription(locationDetails.getDescription());
        location.setPositionX(locationDetails.getPositionX());
        location.setPositionY(locationDetails.getPositionY());
        location.setLocationType(locationDetails.getLocationType());
        location.setParentLocation(locationDetails.getParentLocation());

        return mapLocationRepository.save(location);
    }

    public void deleteMapLocation(Long id) {
        mapLocationRepository.deleteById(id);
    }
}
