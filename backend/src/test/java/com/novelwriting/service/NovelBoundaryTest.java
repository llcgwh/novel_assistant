package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelwriting.controller.*;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, NovelScope.class, SceneService.class, ForeshadowService.class, OutlineService.class,
        TimelineEventService.class, MapLocationService.class, CharacterRelationshipService.class,
        RelationshipGroupService.class, WorldviewEntryService.class, TagService.class, ImageService.class,
        SceneController.class, ForeshadowController.class, OutlineController.class, TimelineEventController.class,
        MapLocationController.class, CharacterRelationshipController.class, RelationshipGroupController.class,
        WorldviewEntryController.class, TagController.class, ImageController.class})
class NovelBoundaryTest {
    @Autowired EntityManager em;
    @Autowired ApplicationContext context;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    record Endpoint(Class<? extends NovelOwned> model, Class<?> controller, String path) {}
    static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint(Scene.class, SceneController.class, "scenes"),
                new Endpoint(Foreshadow.class, ForeshadowController.class, "foreshadows"),
                new Endpoint(Outline.class, OutlineController.class, "outlines"),
                new Endpoint(TimelineEvent.class, TimelineEventController.class, "timeline-events"),
                new Endpoint(MapLocation.class, MapLocationController.class, "map-locations"),
                new Endpoint(CharacterRelationship.class, CharacterRelationshipController.class, "relationships"),
                new Endpoint(RelationshipGroup.class, RelationshipGroupController.class, "relationship-groups"),
                new Endpoint(WorldviewEntry.class, WorldviewEntryController.class, "worldview"),
                new Endpoint(Tag.class, TagController.class, "tags"),
                new Endpoint(Image.class, ImageController.class, "images"));
    }
    static Stream<Endpoint> creatable() { return endpoints().filter(e -> e.model() != Image.class); }
    Novel novel() { Novel n = new Novel(); n.setTitle("Test"); em.persist(n); return n; }
    <T extends NovelOwned> T record(Class<T> type, Novel novel) throws Exception {
        T entity = type.getDeclaredConstructor().newInstance();
        BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(entity);
        bean.setPropertyValue("novelId", novel.getId());
        if (bean.isWritableProperty("name")) bean.setPropertyValue("name", "Original");
        if (bean.isWritableProperty("title")) bean.setPropertyValue("title", "Original");
        if (entity instanceof WorldviewEntry e) e.setCategory("history");
        if (entity instanceof Image e) { e.setFilename("test.png"); e.setFilePath("uploads/test.png"); }
        if (entity instanceof CharacterRelationship e) {
            e.setCharacterId1(record(Character.class, novel).getId());
            e.setCharacterId2(record(Character.class, novel).getId());
        }
        em.persist(entity); em.flush(); return entity;
    }
    MockMvc mvc(Class<?> controller) { return MockMvcBuilders.standaloneSetup(context.getBean(controller)).build(); }
    String path(Novel novel, String suffix) { return "/api/novels/" + novel.getId() + "/" + suffix; }

    @ParameterizedTest @MethodSource("endpoints")
    void foreignNovelCannotReadUpdateOrDelete(Endpoint endpoint) throws Exception {
        Novel owner = novel(), foreign = novel();
        NovelOwned entity = record(endpoint.model(), owner);
        MockMvc mvc = mvc(endpoint.controller());
        String wrong = path(foreign, endpoint.path() + "/" + entity.getId());
        mvc.perform(get(wrong)).andExpect(status().isNotFound());
        if (endpoint.model() != Image.class) {
            mvc.perform(put(wrong).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\",\"title\":\"Changed\"}"))
                    .andExpect(status().isNotFound());
        } else mvc.perform(get(wrong + "/file")).andExpect(status().isNotFound());
        mvc.perform(delete(wrong)).andExpect(status().isNotFound());
        assertNotNull(em.find(endpoint.model(), entity.getId()));
        mvc.perform(get(path(owner, endpoint.path() + "/" + entity.getId()))).andExpect(status().isOk());
    }

    @ParameterizedTest @MethodSource("creatable")
    void creationDoesNotOverwriteClientSuppliedId(Endpoint endpoint) throws Exception {
        Novel owner = novel(); NovelOwned existing = record(endpoint.model(), owner);
        var body = mapper.valueToTree(existing);
        String response = mvc(endpoint.controller()).perform(post(path(owner, endpoint.path()))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNotEquals(existing.getId().longValue(), mapper.readTree(response).get("id").asLong());
        assertNotNull(em.find(endpoint.model(), existing.getId()));
    }

    @Test void forgedNestedTagOwnershipIsRejected() throws Exception {
        Novel owner = novel(), foreign = novel(); Tag tag = record(Tag.class, foreign);
        String body = "{\"name\":\"New\",\"tags\":[{\"id\":" + tag.getId() + ",\"novelId\":" + owner.getId() + "}]}";
        mvc(SceneController.class).perform(post(path(owner, "scenes")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        assertEquals(0L, em.createQuery("select count(s) from Scene s where novelId=:id", Long.class).setParameter("id", owner.getId()).getSingleResult());
    }

    @Test void mixedTagBatchDoesNotClearExistingTags() throws Exception {
        Novel owner = novel(), foreign = novel(); Scene scene = record(Scene.class, owner);
        Tag ownTag = record(Tag.class, owner), foreignTag = record(Tag.class, foreign);
        scene.getTags().add(ownTag); em.flush();
        mvc(SceneController.class).perform(put(path(owner, "scenes/" + scene.getId() + "/tags"))
                .contentType(MediaType.APPLICATION_JSON).content("[" + ownTag.getId() + "," + foreignTag.getId() + "]"))
                .andExpect(status().isNotFound());
        assertEquals(Set.of(ownTag), scene.getTags());
    }

    @Test void mixedTimelineRelationsDoNotClearExistingCharacters() throws Exception {
        Novel owner = novel(), foreign = novel(); TimelineEvent event = record(TimelineEvent.class, owner);
        Character own = record(Character.class, owner), other = record(Character.class, foreign);
        event.getCharacters().add(own); em.flush();
        String body = "{\"characterIds\":[" + own.getId() + "," + other.getId() + "],\"sceneIds\":[],\"foreshadowIds\":[],\"outlineIds\":[]}";
        mvc(TimelineEventController.class).perform(put(path(owner, "timeline-events/" + event.getId() + "/relations"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertEquals(Set.of(own), event.getCharacters());
    }

    @Test void invalidGroupMemberDoesNotCreateOrRenameGroup() throws Exception {
        Novel owner = novel(), foreign = novel(); Character other = record(Character.class, foreign);
        RelationshipGroup group = record(RelationshipGroup.class, owner);
        String body = "{\"name\":\"Changed\",\"characterIds\":[" + other.getId() + "]}";
        MockMvc mvc = mvc(RelationshipGroupController.class);
        mvc.perform(post(path(owner, "relationship-groups")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(put(path(owner, "relationship-groups/" + group.getId())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertEquals("Original", group.getName());
        assertEquals(1L, em.createQuery("select count(g) from RelationshipGroup g where novelId=:id", Long.class).setParameter("id", owner.getId()).getSingleResult());
    }

    @Test void rejectsForeignParentAndParentCycles() throws Exception {
        Novel owner = novel(), foreign = novel();
        MapLocation parent = record(MapLocation.class, owner), child = record(MapLocation.class, owner), outsider = record(MapLocation.class, foreign);
        child.setParentLocation(parent); em.flush();
        MockMvc mvc = mvc(MapLocationController.class);
        mvc.perform(put(path(owner, "map-locations/" + parent.getId())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\",\"parentLocation\":{\"id\":" + outsider.getId() + "}}"))
                .andExpect(status().isNotFound());
        mvc.perform(put(path(owner, "map-locations/" + parent.getId())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\",\"parentLocation\":{\"id\":" + child.getId() + "}}"))
                .andExpect(status().isBadRequest());
        assertNull(parent.getParentLocation());
    }

    @Test void sameNovelReferencesStillWork() throws Exception {
        Novel owner = novel(); Scene scene = record(Scene.class, owner); Tag tag = record(Tag.class, owner);
        mvc(SceneController.class).perform(put(path(owner, "scenes/" + scene.getId() + "/tags"))
                .contentType(MediaType.APPLICATION_JSON).content("[" + tag.getId() + "]")).andExpect(status().isOk());
        assertEquals(Set.of(tag), scene.getTags());
        Character member = record(Character.class, owner);
        mvc(RelationshipGroupController.class).perform(post(path(owner, "relationship-groups"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Team\",\"characterIds\":[" + member.getId() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.characters[0].id").value(member.getId()));
    }
}
