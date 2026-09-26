package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, FormSaveService.class, NovelScope.class, ObjectMapper.class})
class FormSaveTest {
    @Autowired EntityManager em;
    @Autowired FormSaveService forms;
    @Autowired ObjectMapper mapper;
    private long novel() { Novel n = new Novel(); n.setTitle("Forms"); em.persist(n); return n.getId(); }
    private FormSaveService.Form form(String json, Set<Long> tags) throws Exception {
        return new FormSaveService.Form((com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json), tags, null, null, null);
    }
    @Test void retryReusesIdentityAndCanCorrectTheDraftWithoutCreatingAgain() throws Exception {
        long n = novel(); String token = UUID.randomUUID().toString();
        Character first = (Character)forms.save(n, "characters", null, token, form("{\"name\":\"First\",\"id\":999,\"novelId\":999}", Set.of()));
        Character retry = (Character)forms.save(n, "characters", null, token, form("{\"name\":\"Corrected\"}", Set.of()));
        assertEquals(first.getId(), retry.getId()); assertEquals(n, retry.getNovelId());
        assertEquals("Corrected", retry.getName());
        assertEquals(1L, em.createQuery("select count(c) from Character c where c.novelId = :n", Long.class).setParameter("n", n).getSingleResult());
    }
    @Test void invalidAssociationRollsBackBothBodyAndLinks() throws Exception {
        long n = novel(); long other = novel();
        Tag foreign = new Tag(); foreign.setName("foreign"); foreign.setNovelId(other); em.persist(foreign);
        Character existing = (Character)forms.save(n, "characters", null, UUID.randomUUID().toString(), form("{\"name\":\"Original\"}", Set.of()));
        Long id = existing.getId(), foreignId = foreign.getId();
        TestTransaction.flagForCommit(); TestTransaction.end(); TestTransaction.start();
        assertThrows(ResponseStatusException.class, () -> forms.save(n, "characters", id, null, form("{\"name\":\"Changed\"}", Set.of(foreignId))));
        TestTransaction.flagForRollback(); TestTransaction.end(); TestTransaction.start();
        assertEquals("Original", em.find(Character.class, id).getName());
    }
    @Test void savesWorldviewLinksAndClearsThemInOneTransaction() throws Exception {
        long n = novel();
        Character c = new Character(); c.setNovelId(n); c.setName("Hero"); em.persist(c);
        var data = form("{\"name\":\"World\",\"category\":\"history\"}", Set.of()).data();
        var linked = new FormSaveService.Form(data, Set.of(), Set.of(c.getId()), Set.of(), Set.of());
        WorldviewEntry e = (WorldviewEntry)forms.save(n, "worldview", null, UUID.randomUUID().toString(), linked);
        assertEquals(1, e.getCharacters().size());
        forms.save(n, "worldview", e.getId(), null, new FormSaveService.Form(data, Set.of(), Set.of(), Set.of(), Set.of()));
        assertEquals(0, e.getCharacters().size());
    }
    @Test void rejectsCrossNovelIdentityAndParentCycles() throws Exception {
        long n = novel(), other = novel();
        MapLocation m = (MapLocation)forms.save(n, "map-locations", null, UUID.randomUUID().toString(), form("{\"name\":\"Map\"}", null));
        assertThrows(ResponseStatusException.class, () -> forms.save(other, "map-locations", m.getId(), null, form("{\"name\":\"Wrong\"}", null)));
        assertThrows(ResponseStatusException.class, () -> forms.save(n, "map-locations", m.getId(), null, form("{\"name\":\"Map\",\"parentLocation\":{\"id\":"+m.getId()+"}}", null)));
    }
    @Test void retainsOutlineOrderAndForeshadowChapterFields() throws Exception {
        long n = novel();
        Outline o = (Outline)forms.save(n, "outlines", null, UUID.randomUUID().toString(), form("{\"title\":\"Chapter\",\"plotOrder\":4,\"chapterNumber\":2}", Set.of()));
        Foreshadow f = (Foreshadow)forms.save(n, "foreshadows", null, UUID.randomUUID().toString(), form("{\"title\":\"Clue\",\"laidAt\":\"2\",\"revealedAt\":\"8\"}", Set.of()));
        assertEquals(4, o.getPlotOrder()); assertEquals("2", f.getLaidAt()); assertEquals("8", f.getRevealedAt());
    }
    @Test void savesGroupMembersAndSupportsRemovingAParent() throws Exception {
        long n = novel();
        Character c = (Character)forms.save(n, "characters", null, UUID.randomUUID().toString(), form("{\"name\":\"Hero\"}", null));
        RelationshipGroup parent = (RelationshipGroup)forms.save(n, "relationship-groups", null, UUID.randomUUID().toString(), form("{\"name\":\"Parent\"}", null));
        var data = form("{\"name\":\"Child\",\"parentGroupId\":"+parent.getId()+"}", null).data();
        RelationshipGroup child = (RelationshipGroup)forms.save(n, "relationship-groups", null, UUID.randomUUID().toString(), new FormSaveService.Form(data, null, Set.of(c.getId()), null, null));
        assertEquals(parent.getId(), child.getParentGroupId()); assertEquals(1, child.getCharacters().size());
        forms.save(n, "relationship-groups", child.getId(), null, form("{\"name\":\"Child\",\"parentGroupId\":null}", null));
        assertNull(child.getParentGroupId()); assertEquals(1, child.getCharacters().size());
    }
    @Test void relationshipTypeRemainsOptionalAndTokenCannotBeReusedAcrossResources() throws Exception {
        long n = novel(); String token = UUID.randomUUID().toString();
        Character a = (Character)forms.save(n, "characters", null, token, form("{\"name\":\"A\"}", null));
        Character b = (Character)forms.save(n, "characters", null, UUID.randomUUID().toString(), form("{\"name\":\"B\"}", null));
        var relationship = forms.save(n, "relationships", null, UUID.randomUUID().toString(), form("{\"characterId1\":"+a.getId()+",\"characterId2\":"+b.getId()+"}", null));
        assertNotNull(relationship.getId());
        var error = assertThrows(ResponseStatusException.class, () -> forms.save(n, "tags", null, token, form("{\"name\":\"Tag\"}", null)));
        assertEquals(409, error.getStatusCode().value());
    }
}
