package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Shared ownership checks used before any service mutates a record. */
@Component
public class NovelScope {
    @PersistenceContext private EntityManager em;

    public void requireNovel(Long novelId) {
        if (novelId == null || em.find(Novel.class, novelId) == null) throw missing();
    }

    public <T extends NovelOwned> T require(Class<T> type, Long novelId, Long id) {
        if (novelId == null || id == null) throw missing();
        T entity = em.find(type, id);
        if (entity == null || !Objects.equals(entity.getNovelId(), novelId)) throw missing();
        return entity;
    }

    public <T extends NovelOwned> void requireAll(Class<T> type, Long novelId, Set<Long> ids) {
        if (ids == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference IDs are required");
        for (Long id : ids) require(type, novelId, id);
    }

    private <T extends NovelOwned> Set<T> resolve(Class<T> type, Long novelId, Set<T> refs) {
        Set<T> result = new HashSet<>();
        if (refs != null) for (T ref : refs) result.add(require(type, novelId, ref == null ? null : ref.getId()));
        return result;
    }

    // Resolve request-body associations from the database; never trust their supplied novelId or fields.
    public void validateLinks(NovelOwned entity, Long novelId, Long selfId) {
        if (entity instanceof Scene e) {
            e.setTags(resolve(Tag.class, novelId, e.getTags()));
            e.setMapLocations(resolve(MapLocation.class, novelId, e.getMapLocations()));
        } else if (entity instanceof Foreshadow e) e.setTags(resolve(Tag.class, novelId, e.getTags()));
        else if (entity instanceof Outline e) e.setTags(resolve(Tag.class, novelId, e.getTags()));
        else if (entity instanceof TimelineEvent e) {
            e.setTags(resolve(Tag.class, novelId, e.getTags()));
            e.setCharacters(resolve(Character.class, novelId, e.getCharacters()));
            e.setScenes(resolve(Scene.class, novelId, e.getScenes()));
            e.setForeshadows(resolve(Foreshadow.class, novelId, e.getForeshadows()));
            e.setOutlines(resolve(Outline.class, novelId, e.getOutlines()));
        } else if (entity instanceof MapLocation e) {
            e.setTags(resolve(Tag.class, novelId, e.getTags()));
            if (e.getParentLocation() != null) {
                MapLocation parent = require(MapLocation.class, novelId, e.getParentLocation().getId());
                Set<Long> seen = new HashSet<>();
                if (selfId != null) seen.add(selfId);
                for (MapLocation current = parent; current != null; current = current.getParentLocation()) {
                    require(MapLocation.class, novelId, current.getId());
                    if (!seen.add(current.getId())) throw cycle();
                }
                e.setParentLocation(parent);
            }
        } else if (entity instanceof WorldviewEntry e) {
            e.setTags(resolve(Tag.class, novelId, e.getTags()));
            e.setCharacters(resolve(Character.class, novelId, e.getCharacters()));
            e.setScenes(resolve(Scene.class, novelId, e.getScenes()));
            e.setMapLocations(resolve(MapLocation.class, novelId, e.getMapLocations()));
        } else if (entity instanceof RelationshipGroup e) {
            e.setCharacters(resolve(Character.class, novelId, e.getCharacters()));
            Long parentId = e.getParentGroupId();
            Set<Long> seen = new HashSet<>();
            if (selfId != null) seen.add(selfId);
            while (parentId != null) {
                if (!seen.add(parentId)) throw cycle();
                parentId = require(RelationshipGroup.class, novelId, parentId).getParentGroupId();
            }
        } else if (entity instanceof CharacterRelationship e) {
            require(Character.class, novelId, e.getCharacterId1());
            require(Character.class, novelId, e.getCharacterId2());
        }
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found in this novel");
    }
    private ResponseStatusException cycle() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent hierarchy cannot contain a cycle");
    }
}
