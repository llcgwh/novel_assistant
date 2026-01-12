package com.novelwriting.service;

import com.novelwriting.entity.MapLocation;
import com.novelwriting.entity.Tag;
import com.novelwriting.repository.MapLocationRepository;
import com.novelwriting.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class MapLocationService {

    @Autowired
    private MapLocationRepository mapLocationRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<MapLocation> getAllMapLocations() {
        return mapLocationRepository.findAll();
    }

    public List<MapLocation> getMapLocationsByNovelId(Long novelId) {
        return mapLocationRepository.findByNovelId(novelId);
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
        location.setLocationImage(locationDetails.getLocationImage());

        return mapLocationRepository.save(location);
    }

    public void deleteMapLocation(Long id) {
        mapLocationRepository.deleteById(id);
    }

    public List<MapLocation> searchMapLocations(Long novelId, String keyword) {
        return mapLocationRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public List<MapLocation> getMapLocationsByType(Long novelId, String locationType) {
        return mapLocationRepository.findByNovelIdAndLocationType(novelId, locationType);
    }

    public MapLocation addTagToMapLocation(Long locationId, Long tagId) {
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        location.getTags().add(tag);
        return mapLocationRepository.save(location);
    }

    public MapLocation removeTagFromMapLocation(Long locationId, Long tagId) {
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));

        location.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return mapLocationRepository.save(location);
    }

    public MapLocation setMapLocationTags(Long locationId, Set<Long> tagIds) {
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));

        Set<Tag> tags = new java.util.HashSet<>();
        for (Long tagId : tagIds) {
            Tag tag = tagRepository.findById(tagId)
                    .orElseThrow(() -> new RuntimeException("Tag not found: " + tagId));
            tags.add(tag);
        }
        location.setTags(tags);
        return mapLocationRepository.save(location);
    }
}
