package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.Image;
import com.novelwriting.entity.Novel;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.ImageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, BackupBundleService.class, ExportService.class, SeriesService.class, ImportService.class, WritingService.class, WritingDeskService.class})
class BackupBundleTest {
    static final Path ROOT = tempDirectory();
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.upload.dir", ROOT::toString); }
    @Autowired EntityManager em;
    @Autowired BackupBundleService bundles;
    @Autowired ExportService exporter;
    @Autowired ImageRepository images;
    @Autowired WritingService writing;
    @Autowired WritingDeskService desk;
    @SpyBean ImportService importer;
    final ObjectMapper mapper = new ObjectMapper();

    static Path tempDirectory() {
        try { return Files.createTempDirectory("novel-bundle-test-"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    Novel novel(String name) { Novel n = new Novel(); n.setTitle(name); em.persist(n); return n; }
    Image image(Novel novel, String type) throws Exception {
        Path folder = ROOT.resolve(novel.getId().toString()); Files.createDirectories(folder);
        Path path = folder.resolve(UUID.randomUUID() + ".png"); Files.write(path, PNG);
        Image image = new Image(); image.setNovelId(novel.getId()); image.setFilename(path.getFileName().toString());
        image.setFilePath(path.toString()); image.setOriginalName("封面.png"); image.setMimeType("image/png");
        image.setImageType(type); image.setFileSize(PNG.length); em.persist(image); return image;
    }
    String url(Novel novel, Image image) { return "http://old-computer:8080/api/novels/" + novel.getId() + "/images/" + image.getId() + "/file"; }

    @Test void restoresBytesAndUrlsOnAnotherNovelWithoutOriginalFiles() throws Exception {
        Novel source = novel("Source"); Image cover = image(source, "novel_cover");
        source.setCoverImage(url(source, cover));
        Character hero = new Character(); hero.setNovelId(source.getId()); hero.setName("Hero"); hero.setPortraitImage(url(source, cover)); em.persist(hero);
        image(source, "map_background");
        Novel target = novel("Target"); target.setWebdavPassword("keep-target-secret"); target.setDescription("Old description"); image(target, "old");
        em.flush(); TestTransaction.flagForCommit(); TestTransaction.end();
        byte[] backup = bundles.exportBundle(source.getId());
        assertFalse(new String(backup, java.nio.charset.StandardCharsets.UTF_8).contains("filePath"));
        // The source machine's files are no longer available.
        for (Image image : images.findByNovelId(source.getId())) Files.delete(Path.of(image.getFilePath()));
        bundles.restore(target.getId(), backup);
        TestTransaction.start(); em.clear();
        List<Image> restored = images.findByNovelId(target.getId());
        assertEquals(2, restored.size());
        Image restoredCover = restored.stream().filter(i -> i.getImageType().equals("novel_cover")).findFirst().orElseThrow();
        for (Image image : restored) {
            assertArrayEquals(PNG, Files.readAllBytes(Path.of(image.getFilePath())));
            assertTrue(Path.of(image.getFilePath()).startsWith(ROOT.resolve(target.getId().toString())));
        }
        String expectedUrl = "/api/novels/" + target.getId() + "/images/" + restoredCover.getId() + "/file";
        Novel restoredNovel = em.find(Novel.class, target.getId());
        assertEquals(expectedUrl, restoredNovel.getCoverImage()); assertEquals("Source", restoredNovel.getTitle());
        assertEquals("keep-target-secret", restoredNovel.getWebdavPassword());
        assertNull(restoredNovel.getDescription());
        Character restoredHero = em.createQuery("from Character where novelId = :id", Character.class).setParameter("id", target.getId()).getSingleResult();
        assertEquals(expectedUrl, restoredHero.getPortraitImage());
    }

    @Test void damagedOrIncompleteAssetsLeaveExistingDataUntouched() throws Exception {
        Novel novel = novel("Keep"); Image image = image(novel, "novel_cover"); novel.setCoverImage(url(novel, image));
        em.flush(); TestTransaction.flagForCommit(); TestTransaction.end();
        byte[] good = bundles.exportBundle(novel.getId());
        for (String mutation : List.of("checksum", "missing", "duplicate", "mime")) {
            ObjectNode bad = (ObjectNode) mapper.readTree(good);
            var assets = bad.withArray("images");
            switch (mutation) {
                case "checksum" -> ((ObjectNode) assets.get(0)).put("sha256", "wrong");
                case "missing" -> assets.removeAll();
                case "duplicate" -> assets.add(assets.get(0).deepCopy());
                case "mime" -> ((ObjectNode) assets.get(0)).put("mimeType", "text/html");
            }
            assertThrows(IOException.class, () -> bundles.restore(novel.getId(), mapper.writeValueAsBytes(bad)), mutation);
            assertEquals(image.getId(), images.findByNovelId(novel.getId()).get(0).getId());
            assertArrayEquals(PNG, Files.readAllBytes(Path.of(image.getFilePath())));
        }
        TestTransaction.start();
    }

    @Test void failedImportRollsBackRecordsAndRemovesNewFiles() throws Exception {
        Novel novel = novel("Keep"); Image image = image(novel, "novel_cover");
        em.flush(); TestTransaction.flagForCommit(); TestTransaction.end();
        byte[] backup = bundles.exportBundle(novel.getId());
        doThrow(new IOException("Simulated import failure")).when(importer).importFromJson(eq(novel.getId()), any(byte[].class));
        assertThrows(IOException.class, () -> bundles.restore(novel.getId(), backup));
        assertEquals(image.getId(), images.findByNovelId(novel.getId()).get(0).getId());
        try (var files = Files.list(ROOT.resolve(novel.getId().toString()))) { assertEquals(1, files.count()); }
        TestTransaction.start();
    }

    @Test void legacyJsonDoesNotReplaceExistingImages() throws Exception {
        Novel novel = novel("Legacy"); Image image = image(novel, "novel_cover"); em.flush();
        byte[] backup = exporter.exportNovelToJson(novel.getId());
        bundles.restore(novel.getId(), backup);
        assertEquals(image.getId(), images.findByNovelId(novel.getId()).get(0).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nextPen", "tasks", "bookmarks"})
    void oldImageAndDataBackupsCannotClearCreativeNotes(String kind) throws Exception {
        Novel novel = novel("保留新便签");
        Image cover = image(novel, "novel_cover");
        ObjectNode chapter = writing.create(novel.getId(), mapper.createObjectNode()
                .put("uid", UUID.randomUUID().toString()).put("title", "保留正文"));
        ObjectNode data = WritingDeskDocuments.emptyDesk().put("mutationId", UUID.randomUUID().toString());
        ObjectNode note = mapper.createObjectNode().put("chapterUid", chapter.path("uid").asText())
                .put("blockId", "").put("excerpt", "已有摘录");
        switch (kind) {
            case "nextPen" -> data.set("nextPen", note.put("nextScene", "明天继续")
                    .put("question", "").put("opening", "").put("updatedAt", "2026-09-28T12:00:00Z"));
            case "tasks" -> data.withArray("tasks").add(note.put("uid", UUID.randomUUID().toString())
                    .put("body", "已有修订").put("category", "other").put("priority", "normal").put("status", "open")
                    .put("createdAt", "2026-09-28T12:00:00Z").put("updatedAt", "2026-09-28T12:00:00Z"));
            case "bookmarks" -> data.withArray("bookmarks").add(note.put("uid", UUID.randomUUID().toString())
                    .put("label", "已有书签").put("createdAt", "2026-09-28T12:00:00Z"));
        }
        ObjectNode saved = desk.save(novel.getId(), data);
        ObjectNode legacyBundle = (ObjectNode) mapper.readTree(bundles.exportBundle(novel.getId()));
        ((ObjectNode) legacyBundle.path("data").path("writing")).remove("desk");
        // The HTTP restore and cloud pull both use this bundle entry point.
        var blocked = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> bundles.restore(novel.getId(), mapper.writeValueAsBytes(legacyBundle)));
        assertTrue(blocked.getReason().contains("旧备份不含创作便签"));
        verify(importer, never()).importFromJson(eq(novel.getId()), any(byte[].class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> importer.importFromJson(novel.getId(), mapper.writeValueAsBytes(legacyBundle.path("data"))));
        assertEquals(saved, desk.get(novel.getId()));
        assertEquals(chapter, writing.get(novel.getId(), chapter.path("uid").asText()));
        assertEquals(cover.getId(), images.findByNovelId(novel.getId()).get(0).getId());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(cover.getFilePath())));
        try (var files = Files.list(ROOT.resolve(novel.getId().toString()))) { assertEquals(1, files.count()); }
    }

    @Test void refusesImageFilesOutsideNovelDirectory() throws Exception {
        Novel novel = novel("Unsafe"); Image image = image(novel, "novel_cover");
        image.setFilePath(ROOT.resolve("outside.png").toString()); Files.write(Path.of(image.getFilePath()), PNG); em.flush();
        assertThrows(IOException.class, () -> bundles.exportBundle(novel.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"desk", "rounds"})
    void oldBackupsCannotClearRevisionRoundsBeforeImagesOrDataAreChanged(String missing) throws Exception {
        Novel novel = novel("保留修订轮次");
        Image cover = image(novel, "novel_cover");
        ObjectNode chapter = writing.create(novel.getId(), mapper.createObjectNode()
                .put("uid", UUID.randomUUID().toString()).put("title", "保留正文"));
        ObjectNode data = WritingDeskDocuments.emptyDesk().put("mutationId", UUID.randomUUID().toString());
        data.withArray("rounds").add(mapper.createObjectNode().put("uid", "round-1").put("title", "人物修订")
                .put("goal", "逐章检查动机").put("status", "active")
                .put("createdAt", "2026-09-28T12:00:00Z").put("updatedAt", "2026-09-28T12:00:00Z"));
        data.withArray("tasks").add(mapper.createObjectNode().put("uid", "task-1")
                .put("chapterUid", chapter.path("uid").asText()).put("blockId", "").put("excerpt", "")
                .put("body", "保留任务分组").put("roundUid", "round-1").put("category", "other")
                .put("priority", "normal").put("status", "open")
                .put("createdAt", "2026-09-28T12:00:00Z").put("updatedAt", "2026-09-28T12:00:00Z"));
        ObjectNode saved = desk.save(novel.getId(), data);
        ObjectNode legacyBundle = (ObjectNode) mapper.readTree(bundles.exportBundle(novel.getId()));
        ObjectNode backupWriting = (ObjectNode) legacyBundle.path("data").path("writing");
        if (missing.equals("desk")) backupWriting.remove("desk");
        else {
            ((ObjectNode) backupWriting.path("desk")).remove("rounds");
            ((ObjectNode) backupWriting.path("desk").path("tasks").get(0)).remove("roundUid");
        }
        var blocked = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> bundles.restore(novel.getId(), mapper.writeValueAsBytes(legacyBundle)));
        assertEquals(400, blocked.getStatusCode().value());
        assertTrue(blocked.getReason().contains("旧备份不含修订轮次"));
        verify(importer, never()).importFromJson(eq(novel.getId()), any(byte[].class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> importer.importFromJson(novel.getId(), mapper.writeValueAsBytes(legacyBundle.path("data"))));
        assertEquals(saved, desk.get(novel.getId()));
        assertEquals(chapter, writing.get(novel.getId(), chapter.path("uid").asText()));
        assertEquals(cover.getId(), images.findByNovelId(novel.getId()).get(0).getId());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(cover.getFilePath())));
        try (var files = Files.list(ROOT.resolve(novel.getId().toString()))) { assertEquals(1, files.count()); }
    }

    @Test void boundedReaderRejectsOversizedInput() {
        assertThrows(IOException.class, () -> BackupBundleService.readLimited(new ByteArrayInputStream(new byte[11]), 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"desk", "ideas"})
    void ideaBundlesRoundTripAndLegacyRestoresProtectIdeasBeforeChangingImages(String missing) throws Exception {
        Novel source = novel("灵感来源"), target = novel("恢复目标");
        Image cover = image(source, "novel_cover");
        ObjectNode chapter = writing.create(source.getId(), mapper.createObjectNode()
                .put("uid", UUID.randomUUID().toString()).put("title", "海上的信"));
        ObjectNode data = WritingDeskDocuments.emptyDesk().put("mutationId", UUID.randomUUID().toString());
        ObjectNode idea = data.withArray("ideas").addObject().put("uid", "legacy-note-id")
                .put("title", "旧便笺").put("body", "  保留旧文换行\n海鸟带来的线索。\n")
                .put("category", "plot").put("sourceKey", "legacy:content-sha256")
                .put("createdAt", "2026-10-04T12:00:00Z").put("updatedAt", "2026-10-04T12:00:00Z");
        idea.putArray("chapterUids").add(chapter.path("uid").asText());
        ObjectNode saved = desk.save(source.getId(), data);
        byte[] bundle = bundles.exportBundle(source.getId());
        bundles.restore(target.getId(), bundle);
        assertEquals(saved.path("ideas"), desk.get(target.getId()).path("ideas"));
        assertEquals(chapter.path("uid"), writing.get(target.getId(), chapter.path("uid").asText()).path("uid"));
        ObjectNode legacy = (ObjectNode) mapper.readTree(bundle);
        ObjectNode backupWriting = (ObjectNode) legacy.path("data").path("writing");
        if (missing.equals("desk")) backupWriting.remove("desk");
        else ((ObjectNode) backupWriting.path("desk")).remove("ideas");
        clearInvocations(importer);
        var blocked = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> bundles.restore(source.getId(), mapper.writeValueAsBytes(legacy)));
        assertEquals(400, blocked.getStatusCode().value());
        assertTrue(blocked.getReason().contains("旧备份不含灵感收件箱"));
        verify(importer, never()).importFromJson(eq(source.getId()), any(byte[].class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> importer.importFromJson(source.getId(), mapper.writeValueAsBytes(legacy.path("data"))));
        assertEquals(saved, desk.get(source.getId()));
        assertEquals(chapter, writing.get(source.getId(), chapter.path("uid").asText()));
        assertEquals(cover.getId(), images.findByNovelId(source.getId()).get(0).getId());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(cover.getFilePath())));
    }
}
