package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.SceneRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, ImportService.class, ExportService.class})
class BackupRoundTripTest {
    @Autowired EntityManager em;
    @Autowired ImportService importer;
    @Autowired ExportService exporter;
    @SpyBean SceneRepository scenes;
    final ObjectMapper mapper = new ObjectMapper();

    private <T> T persist(T entity) { em.persist(entity); return entity; }

    private Novel seed() {
        Novel novel = new Novel(); novel.setTitle("Backup test"); persist(novel);
        long id = novel.getId();
        Tag tag = new Tag(); tag.setNovelId(id); tag.setName("Important"); persist(tag);
        Character character = new Character(); character.setNovelId(id); character.setName("Hero");
        character.setTags(Set.of(tag)); persist(character);
        MapLocation parent = new MapLocation(); parent.setNovelId(id); parent.setName("Country"); persist(parent);
        MapLocation location = new MapLocation(); location.setNovelId(id); location.setName("Town");
        location.setParentLocation(parent); location.setTags(Set.of(tag)); persist(location);
        Scene scene = new Scene(); scene.setNovelId(id); scene.setName("Meeting");
        scene.setTags(Set.of(tag)); scene.setMapLocations(Set.of(location)); persist(scene);
        Foreshadow f = new Foreshadow(); f.setNovelId(id); f.setTitle("Clue"); f.setTags(Set.of(tag)); persist(f);
        Outline o = new Outline(); o.setNovelId(id); o.setTitle("Chapter"); o.setTags(Set.of(tag)); persist(o);
        TimelineEvent t = new TimelineEvent(); t.setNovelId(id); t.setTitle("Event"); t.setTags(Set.of(tag));
        t.setCharacters(Set.of(character)); t.setScenes(Set.of(scene)); t.setForeshadows(Set.of(f)); t.setOutlines(Set.of(o)); persist(t);
        WorldviewEntry w = new WorldviewEntry(); w.setNovelId(id); w.setName("Setting"); w.setCategory("history");
        w.setTags(Set.of(tag)); w.setCharacters(Set.of(character)); w.setScenes(Set.of(scene)); w.setMapLocations(Set.of(location)); persist(w);
        RelationshipGroup group = new RelationshipGroup(); group.setNovelId(id); group.setName("Family"); persist(group);
        RelationshipGroup child = new RelationshipGroup(); child.setNovelId(id); child.setName("Branch");
        child.setParentGroupId(group.getId()); child.setCharacters(Set.of(character)); persist(child);
        em.flush();
        return novel;
    }

    private <T> T one(Class<T> type, long novelId, String name) {
        return em.createQuery("from " + type.getSimpleName() + " where novelId = :novelId and name = :name", type)
                .setParameter("novelId", novelId).setParameter("name", name).getSingleResult();
    }

    @Test void restoresTagsGroupsAndSceneLocationsWithNewIds() throws Exception {
        Novel novel = seed(); long id = novel.getId();
        long oldCharacterId = one(Character.class, id, "Hero").getId();
        Novel other = new Novel(); other.setTitle("Untouched"); persist(other);
        Character outsider = new Character(); outsider.setNovelId(other.getId()); outsider.setName("Outsider"); persist(outsider);
        em.flush();
        ObjectNode backup = (ObjectNode) mapper.readTree(exporter.exportNovelToJson(id));
        var groups = backup.withArray("relationshipGroups");
        var first = groups.remove(0); groups.add(first); // Child before parent.
        importer.importFromJson(id, mapper.writeValueAsBytes(backup));
        em.flush(); em.clear();
        Character hero = one(Character.class, id, "Hero");
        assertNotEquals(oldCharacterId, hero.getId());
        assertEquals("Important", hero.getTags().iterator().next().getName());
        assertNotNull(em.find(Character.class, outsider.getId()));
        for (String entity : new String[]{"Scene", "Foreshadow", "Outline", "TimelineEvent", "WorldviewEntry"}) {
            assertEquals(1L, em.createQuery("select count(t) from " + entity + " e join e.tags t where e.novelId = :id", Long.class)
                    .setParameter("id", id).getSingleResult());
        }
        MapLocation town = one(MapLocation.class, id, "Town");
        assertEquals("Country", town.getParentLocation().getName());
        assertEquals(1, town.getTags().size());
        assertEquals(town.getId(), one(Scene.class, id, "Meeting").getMapLocations().iterator().next().getId());
        RelationshipGroup child = one(RelationshipGroup.class, id, "Branch");
        assertEquals(one(RelationshipGroup.class, id, "Family").getId(), child.getParentGroupId());
        assertEquals(hero.getId(), child.getCharacters().iterator().next().getId());
        TimelineEvent event = em.createQuery("from TimelineEvent where novelId = :id", TimelineEvent.class).setParameter("id", id).getSingleResult();
        assertEquals(hero.getId(), event.getCharacters().iterator().next().getId());
        assertEquals(1, event.getScenes().size()); assertEquals(1, event.getForeshadows().size()); assertEquals(1, event.getOutlines().size());
        WorldviewEntry world = one(WorldviewEntry.class, id, "Setting");
        assertEquals(hero.getId(), world.getCharacters().iterator().next().getId());
        assertEquals(town.getId(), world.getMapLocations().iterator().next().getId());
        assertEquals(1, world.getScenes().size());
        // Re-exported data must itself be a valid backup.
        BackupValidator.validate(mapper.readTree(exporter.exportNovelToJson(id)));
    }

    @Test void rejectsInvalidBackupsBeforeDeletingAnything() throws Exception {
        Novel novel = seed(); long id = novel.getId();
        byte[] good = exporter.exportNovelToJson(id);
        for (String mutation : new String[]{"missing", "duplicate", "reference", "cycle", "mapCycle", "version", "empty"}) {
            ObjectNode bad = (ObjectNode) mapper.readTree(good);
            switch (mutation) {
                case "missing" -> bad.remove("scenes");
                case "duplicate" -> bad.withArray("characters").add(bad.withArray("characters").get(0).deepCopy());
                case "reference" -> ((ObjectNode) bad.withArray("scenes").get(0).path("tags").get(0)).put("id", 99999999L);
                case "cycle" -> {
                    ObjectNode g = (ObjectNode) bad.withArray("relationshipGroups").get(0);
                    g.put("parentGroupId", g.get("id").asLong());
                }
                case "mapCycle" -> {
                    ObjectNode location = (ObjectNode) bad.withArray("mapLocations").get(0);
                    location.putObject("parentLocation").put("id", location.get("id").asLong());
                }
                case "version" -> bad.put("schemaVersion", 100);
                case "empty" -> bad.removeAll();
            }
            assertThrows(IllegalArgumentException.class, () -> importer.importFromJson(id, mapper.writeValueAsBytes(bad)), mutation);
            assertEquals(1L, em.createQuery("select count(c) from Character c where c.novelId = :id", Long.class).setParameter("id", id).getSingleResult());
        }
    }

    @Test void acceptsLegacyBackupWithoutGroupsOrVersion() throws Exception {
        Novel novel = seed(); long id = novel.getId();
        ObjectNode backup = (ObjectNode) mapper.readTree(exporter.exportNovelToJson(id));
        backup.remove("schemaVersion"); backup.remove("relationshipGroups");
        importer.importFromJson(id, mapper.writeValueAsBytes(backup));
        em.flush(); em.clear();
        assertEquals("Hero", one(Character.class, id, "Hero").getName());
        assertEquals(0L, em.createQuery("select count(g) from RelationshipGroup g where g.novelId = :id", Long.class).setParameter("id", id).getSingleResult());
    }

    @Test void failedRestoreRollsBackDeletedData() throws Exception {
        Novel novel = seed(); long id = novel.getId();
        long originalId = one(Character.class, id, "Hero").getId();
        TestTransaction.flagForCommit(); TestTransaction.end();
        byte[] backup = exporter.exportNovelToJson(id); // Export also works without an open caller transaction.
        doThrow(new IllegalStateException("Simulated storage failure")).when(scenes).save(any(Scene.class));
        assertThrows(IllegalStateException.class, () -> importer.importFromJson(id, backup));
        TestTransaction.start(); em.clear();
        assertEquals(originalId, one(Character.class, id, "Hero").getId());
        assertEquals("Meeting", one(Scene.class, id, "Meeting").getName());
        assertEquals(1, one(RelationshipGroup.class, id, "Branch").getCharacters().size());
    }
}
