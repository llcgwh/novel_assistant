package com.novelwriting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.NovelRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, NovelService.class})
class NovelDeletionTest {
    private static final List<String> OWNED = List.of(
            "RelationshipGroup", "CharacterRelationship", "WorldviewEntry", "TimelineEvent",
            "Foreshadow", "Outline", "Scene", "MapLocation", "Character", "Tag", "Image",
            "WritingRevision", "WritingSession", "WritingChapter", "WritingBook",
            "FormSubmission", "BackupCleanup", "BackupRetention");
    private static final List<String> JOINS = List.of(
            "relationship_group_characters", "worldview_entry_tags", "worldview_entry_characters",
            "worldview_entry_scenes", "worldview_entry_map_locations", "timeline_event_characters",
            "timeline_event_scenes", "timeline_event_foreshadows", "timeline_event_outlines",
            "timeline_event_tags", "scene_tags", "scene_locations", "map_location_tags",
            "character_tags", "foreshadow_tags", "outline_tags");

    @Autowired EntityManager em;
    @Autowired NovelService service;
    @SpyBean NovelRepository novels;

    private <T> T persist(T value) { em.persist(value); return value; }
    private String uid() { return UUID.randomUUID().toString(); }

    private Novel seed() {
        Novel novel = new Novel(); novel.setTitle("关联完整的作品"); persist(novel);
        long id = novel.getId();
        Tag tag = new Tag(); tag.setNovelId(id); tag.setName("标签"); persist(tag);
        Character hero = new Character(); hero.setNovelId(id); hero.setName("主角");
        hero.getTags().add(tag); persist(hero);
        Character partner = new Character(); partner.setNovelId(id); partner.setName("同行者"); persist(partner);
        CharacterRelationship relationship = new CharacterRelationship(); relationship.setNovelId(id);
        relationship.setCharacterId1(hero.getId()); relationship.setCharacterId2(partner.getId()); persist(relationship);
        RelationshipGroup parent = new RelationshipGroup(); parent.setNovelId(id); parent.setName("根关系组"); persist(parent);
        RelationshipGroup child = new RelationshipGroup(); child.setNovelId(id); child.setName("子关系组");
        child.setParentGroupId(parent.getId()); child.getCharacters().add(hero); persist(child);
        MapLocation country = new MapLocation(); country.setNovelId(id); country.setName("国家"); persist(country);
        MapLocation town = new MapLocation(); town.setNovelId(id); town.setName("城镇");
        town.setParentLocation(country); town.getTags().add(tag); persist(town);
        Scene scene = new Scene(); scene.setNovelId(id); scene.setName("见面");
        scene.getTags().add(tag); scene.getMapLocations().add(town); persist(scene);
        Foreshadow clue = new Foreshadow(); clue.setNovelId(id); clue.setTitle("伏笔"); clue.getTags().add(tag); persist(clue);
        Outline outline = new Outline(); outline.setNovelId(id); outline.setTitle("大纲"); outline.getTags().add(tag); persist(outline);
        TimelineEvent event = new TimelineEvent(); event.setNovelId(id); event.setTitle("事件");
        event.getCharacters().add(hero); event.getScenes().add(scene); event.getForeshadows().add(clue);
        event.getOutlines().add(outline); event.getTags().add(tag); persist(event);
        WorldviewEntry world = new WorldviewEntry(); world.setNovelId(id); world.setName("设定"); world.setCategory("history");
        world.getTags().add(tag); world.getCharacters().add(hero); world.getScenes().add(scene); world.getMapLocations().add(town); persist(world);
        Image image = new Image(); image.setNovelId(id); image.setFilename("original.png"); image.setFilePath("untouched/original.png"); persist(image);
        WritingBook book = new WritingBook(); book.setNovelId(id); book.setUid(uid()); book.setDeskData("{\"note\":\"保留直至整个删除事务成功\"}"); persist(book);
        WritingChapter chapter = new WritingChapter(); chapter.setNovelId(id); chapter.setUid(uid()); chapter.setTitle("正文"); chapter.setDocument("{}"); persist(chapter);
        WritingRevision revision = new WritingRevision(); revision.setNovelId(id); revision.setChapterUid(chapter.getUid());
        revision.setSnapshot("{}"); revision.setCreatedAt(LocalDateTime.now()); persist(revision);
        WritingSession session = new WritingSession(); session.setNovelId(id); session.setUid(uid()); session.setPayload("{}"); persist(session);
        FormSubmission submission = new FormSubmission(); submission.setNovelId(id); submission.setToken(uid());
        submission.setResource("characters"); submission.setRecordId(hero.getId()); persist(submission);
        BackupCleanup cleanup = new BackupCleanup(); cleanup.setNovelId(id); cleanup.setToken(uid());
        cleanup.setExpiresAt(Instant.now().plusSeconds(600)); cleanup.setStatus("PREVIEW"); cleanup.setPreview("{}"); persist(cleanup);
        BackupRetention retention = new BackupRetention(); retention.setNovelId(id); persist(retention);
        BackupOperation audit = new BackupOperation(); audit.setNovelId(id); audit.setType("backup");
        audit.setStatus("SUCCEEDED"); audit.setStartedAt(Instant.now()); persist(audit);
        receipt("novel:" + id + ":" + uid());
        em.flush();
        return novel;
    }

    private SeriesMutationReceipt receipt(String scope) {
        SeriesMutationReceipt receipt = new SeriesMutationReceipt(); receipt.setId(uid()); receipt.setScope(scope);
        receipt.setMutationId(uid()); receipt.setRequestHash("hash"); receipt.setResult("{}"); return persist(receipt);
    }

    private Map<String, Long> counts(long novelId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String entity : OWNED) result.put(entity, em.createQuery("select count(e) from " + entity + " e where novelId=:id", Long.class)
                .setParameter("id", novelId).getSingleResult());
        return result;
    }

    private Map<String, Long> joinCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : JOINS) result.put(table, ((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue());
        return result;
    }

    @Test void deletesEveryOwnedDependencyWithoutRelyingOnSqlCascadesAndKeepsOtherWorks() {
        // Reproduce the upgraded PostgreSQL shape: old characters cascade from the
        // novel, but Hibernate's newer relationship-group membership FK does not.
        em.createNativeQuery("alter table characters add constraint if not exists deletion_test_character_novel "
                + "foreign key (novel_id) references novels(id) on delete cascade").executeUpdate();
        Novel removed = seed(), kept = seed();
        Map<String, Long> keptCounts = counts(kept.getId()), allJoins = joinCounts();
        SeriesMutationReceipt catalogReceipt = receipt("catalog");
        SeriesMutationReceipt prefixNeighbor = receipt("novel:" + removed.getId() + "0:" + uid());
        SeriesTemplate template = new SeriesTemplate(); template.setUid(uid()); template.setKind("worldview");
        template.setHeadRevisionUid(uid()); template.setCreatedAt("now"); template.setUpdatedAt("now"); persist(template);
        em.flush(); em.clear();

        service.deleteNovel(removed.getId());
        em.clear();
        assertNull(em.find(Novel.class, removed.getId()));
        assertNotNull(em.find(Novel.class, kept.getId()));
        counts(removed.getId()).forEach((entity, count) -> assertEquals(0L, count, entity));
        assertEquals(keptCounts, counts(kept.getId()));
        joinCounts().forEach((table, count) -> assertEquals(allJoins.get(table) / 2, count, table));
        assertEquals(0L, em.createQuery("select count(r) from SeriesMutationReceipt r where scope like :scope", Long.class)
                .setParameter("scope", "novel:" + removed.getId() + ":%").getSingleResult());
        assertNotNull(em.find(SeriesMutationReceipt.class, catalogReceipt.getId()));
        assertNotNull(em.find(SeriesMutationReceipt.class, prefixNeighbor.getId()));
        assertNotNull(em.find(SeriesTemplate.class, template.getUid()));
        assertEquals(1L, em.createQuery("select count(b) from BackupOperation b where novelId=:id", Long.class)
                .setParameter("id", removed.getId()).getSingleResult());
        // A repeated delete is harmless and cannot affect the remaining work.
        service.deleteNovel(removed.getId());
        assertEquals(keptCounts, counts(kept.getId()));
    }

    @Test void failingFinalDeleteRollsBackAllRemovedRowsAndJoinTables() {
        Novel novel = seed();
        Map<String, Long> before = counts(novel.getId()), beforeJoins = joinCounts();
        TestTransaction.flagForCommit(); TestTransaction.end();
        doThrow(new IllegalStateException("Simulated final delete failure")).when(novels).deleteById(anyLong());
        assertThrows(IllegalStateException.class, () -> service.deleteNovel(novel.getId()));
        TestTransaction.start(); em.clear();
        assertNotNull(em.find(Novel.class, novel.getId()));
        assertEquals(before, counts(novel.getId()));
        assertEquals(beforeJoins, joinCounts());
        assertEquals(1L, em.createQuery("select count(r) from SeriesMutationReceipt r where scope like :scope", Long.class)
                .setParameter("scope", "novel:" + novel.getId() + ":%").getSingleResult());
    }
}
