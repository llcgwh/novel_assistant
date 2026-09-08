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
@org.springframework.transaction.annotation.Transactional
public class MapLocationService {

    @Autowired
    private NovelScope scope;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    @Autowired
    private TagRepository tagRepository;

    public List<MapLocation> getMapLocationsByNovelId(Long novelId) {
        return mapLocationRepository.findByNovelIdWithTags(novelId);
    }

    public Optional<MapLocation> getMapLocationById(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, id);
        return mapLocationRepository.findById(id);
    }

    public MapLocation createMapLocation(MapLocation mapLocation) {
        Long novelId = mapLocation.getNovelId();
        scope.requireNovel(novelId);
        mapLocation.setId(null);
        scope.validateLinks(mapLocation, novelId, null);
        return mapLocationRepository.save(mapLocation);
    }

    public MapLocation updateMapLocation(Long novelId, Long id, MapLocation locationDetails) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, id);
        scope.validateLinks(locationDetails, novelId, id);
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

    public void deleteMapLocation(Long novelId, Long id) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, id);
        mapLocationRepository.deleteById(id);
    }

    public List<MapLocation> searchMapLocations(Long novelId, String keyword) {
        return mapLocationRepository.searchByNovelIdAndKeyword(novelId, keyword);
    }

    public List<MapLocation> getMapLocationsByType(Long novelId, String locationType) {
        return mapLocationRepository.findByNovelIdAndLocationType(novelId, locationType);
    }

    public MapLocation addTagToMapLocation(Long novelId, Long locationId, Long tagId) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new RuntimeException("Tag not found"));

        location.getTags().add(tag);
        return mapLocationRepository.save(location);
    }

    public MapLocation removeTagFromMapLocation(Long novelId, Long locationId, Long tagId) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        scope.require(com.novelwriting.entity.Tag.class, novelId, tagId);
        MapLocation location = mapLocationRepository.findById(locationId)
                .orElseThrow(() -> new RuntimeException("Map Location not found"));

        location.getTags().removeIf(tag -> tag.getId().equals(tagId));
        return mapLocationRepository.save(location);
    }

    public MapLocation setMapLocationTags(Long novelId, Long locationId, Set<Long> tagIds) {
        scope.require(com.novelwriting.entity.MapLocation.class, novelId, locationId);
        scope.requireAll(com.novelwriting.entity.Tag.class, novelId, tagIds);
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
