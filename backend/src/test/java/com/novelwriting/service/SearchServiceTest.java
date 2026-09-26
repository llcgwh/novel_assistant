package com.novelwriting.service;

import com.novelwriting.entity.Novel;
import com.novelwriting.entity.Tag;
import com.novelwriting.entity.WorldviewEntry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, SearchService.class, WritingService.class})
class SearchServiceTest {
    @Autowired EntityManager em;
    @Autowired SearchService search;

    private Novel novel() {
        Novel novel = new Novel(); novel.setTitle("Search test"); em.persist(novel); return novel;
    }
    private Tag tag(long novelId, String name) {
        Tag tag = new Tag(); tag.setNovelId(novelId); tag.setName(name); em.persist(tag); return tag;
    }
    private WorldviewEntry entry(long novelId, String name, String content, Tag... tags) {
        WorldviewEntry entry = new WorldviewEntry(); entry.setNovelId(novelId);
        entry.setName(name); entry.setCategory("history"); entry.setContent(content);
        entry.setTags(Set.of(tags)); em.persist(entry); return entry;
    }

    @Test void keywordSearchIncludesWorldviewNamesAndContentWithinTheSelectedNovel() {
        long id = novel().getId(); long other = novel().getId();
        WorldviewEntry nameMatch = entry(id, "Moon Gate", "A setting");
        WorldviewEntry contentMatch = entry(id, "Archive", "History of the moon");
        entry(other, "Moon Gate", "Other novel");
        entry(id, "Unrelated", "Other setting"); em.flush();
        var results = search.globalSearch(id, "  mOoN  ");
        assertEquals(Set.of(nameMatch, contentMatch), Set.copyOf((List<?>) results.get("worldviewEntries")));
        assertTrue(results.containsKey("characters"));
    }

    @Test void tagSearchIncludesWorldviewAndDeduplicatesMultipleMatchingTags() {
        long id = novel().getId(); long other = novel().getId();
        Tag first = tag(id, "月门历史"); Tag second = tag(id, "月门传说");
        Tag foreign = tag(other, "月门历史");
        WorldviewEntry match = entry(id, "月门", "", first, second);
        entry(other, "月门", "", foreign); em.flush();
        assertEquals(List.of(match), search.searchByTag(id, first.getId()).get("worldviewEntries"));
        assertEquals(List.of(match), search.searchByTagName(id, "月门").get("worldviewEntries"));
        assertEquals(List.of(), search.searchByTag(id, foreign.getId()).get("worldviewEntries"));
    }

    @Test void missingTagReturnsEmptyWorldviewSection() {
        long id = novel().getId();
        entry(id, "Moon Gate", ""); em.flush();
        assertEquals(List.of(), search.searchByTagName(id, "missing").get("worldviewEntries"));
    }
}
