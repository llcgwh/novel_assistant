package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class FormSaveService {
    public record Form(ObjectNode data, Set<Long> tagIds, Set<Long> characterIds,
                       Set<Long> sceneIds, Set<Long> mapLocationIds) {}
    private record Resource(Class<? extends NovelOwned> type, String fields, String required) {}
    private static final Map<String, Resource> RESOURCES = Map.of(
        "characters", new Resource(Character.class, "name,role,description,personality,appearance,background,portraitImage", "name"),
        "scenes", new Resource(Scene.class, "name,location,description,atmosphere,sceneImage", "name"),
        "foreshadows", new Resource(Foreshadow.class, "title,content,status,laidAt,revealedAt", "title"),
        "outlines", new Resource(Outline.class, "title,content,chapterNumber,plotOrder,status", "title"),
        "timeline-events", new Resource(TimelineEvent.class, "title,eventTime,realOrder,description", "title"),
        "map-locations", new Resource(MapLocation.class, "name,locationType,description,positionX,positionY,parentLocation,locationImage", "name"),
        "worldview", new Resource(WorldviewEntry.class, "name,category,content,entryImage", "name"),
        "tags", new Resource(Tag.class, "name,color,description", "name"),
        "relationships", new Resource(CharacterRelationship.class, "characterId1,characterId2,relationshipType,description", "characterId1"),
        "relationship-groups", new Resource(RelationshipGroup.class, "name,description,parentGroupId", "name")
    );
    @PersistenceContext private EntityManager em;
    private final NovelScope scope;
    private final ObjectMapper mapper;
    public FormSaveService(NovelScope scope, ObjectMapper mapper) { this.scope = scope; this.mapper = mapper; }

    @Transactional
    public NovelOwned save(Long novelId, String resource, Long id, String token, Form form) {
        Resource config = RESOURCES.get(resource);
        if (config == null || form == null || form.data() == null) throw bad("Invalid form");
        // Serialize submissions for one novel so two simultaneous retries cannot create two records.
        if (em.find(Novel.class, novelId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Novel not found");
        FormSubmission submission = null;
        if (id == null) {
            try { token = UUID.fromString(token).toString(); } catch (Exception e) { throw bad("A UUID submission token is required"); }
            submission = em.find(FormSubmission.class, token);
            if (submission != null) {
                if (!submission.getNovelId().equals(novelId) || !submission.getResource().equals(resource))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission token belongs to another form");
                id = submission.getRecordId();
            }
        }
        ObjectNode data = mapper.createObjectNode();
        for (String field : config.fields().split(","))
            if (form.data().has(field)) data.set(field, form.data().get(field));
        if (data.path(config.required()).asText("").isBlank()) throw bad("Name or title is required");
        if (resource.equals("worldview") && data.path("category").asText("").isBlank()) throw bad("Category is required");
        boolean creating = id == null;
        NovelOwned entity;
        try {
            if (creating) {
                data.put("novelId", novelId);
                entity = mapper.treeToValue(data, config.type());
            } else {
                entity = scope.require(config.type(), novelId, id);
                mapper.readerForUpdating(entity).readValue(data);
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw bad("Invalid form fields"); }
        var wrapper = new BeanWrapperImpl(entity);
        setLinks(wrapper, "tags", Tag.class, novelId, form.tagIds());
        setLinks(wrapper, "characters", Character.class, novelId, form.characterIds());
        setLinks(wrapper, "scenes", Scene.class, novelId, form.sceneIds());
        setLinks(wrapper, "mapLocations", MapLocation.class, novelId, form.mapLocationIds());
        scope.validateLinks(entity, novelId, id);
        if (entity instanceof CharacterRelationship relationship && Objects.equals(relationship.getCharacterId1(), relationship.getCharacterId2()))
            throw bad("Choose two different characters");
        if (creating) em.persist(entity);
        em.flush();
        if (creating) {
            submission = new FormSubmission(); submission.setToken(token); submission.setNovelId(novelId);
            submission.setResource(resource); submission.setRecordId(entity.getId()); em.persist(submission);
        }
        return entity;
    }

    private <T extends NovelOwned> void setLinks(BeanWrapperImpl target, String property, Class<T> type, Long novelId, Set<Long> ids) {
        if (ids == null) return; // An omitted section preserves the existing associations.
        if (!target.isWritableProperty(property)) throw bad("Unsupported association");
        Set<T> links = new HashSet<>();
        for (Long id : ids) links.add(scope.require(type, novelId, id));
        target.setPropertyValue(property, links);
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
