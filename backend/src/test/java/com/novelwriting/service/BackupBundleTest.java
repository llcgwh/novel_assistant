package com.novelwriting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.Image;
import com.novelwriting.entity.Novel;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.ImageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
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
@Import({com.novelwriting.security.TestCredentials.class, BackupBundleService.class, ExportService.class, ImportService.class})
class BackupBundleTest {
    static final Path ROOT = tempDirectory();
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.upload.dir", ROOT::toString); }
    @Autowired EntityManager em;
    @Autowired BackupBundleService bundles;
    @Autowired ExportService exporter;
    @Autowired ImageRepository images;
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

    @Test void refusesImageFilesOutsideNovelDirectory() throws Exception {
        Novel novel = novel("Unsafe"); Image image = image(novel, "novel_cover");
        image.setFilePath(ROOT.resolve("outside.png").toString()); Files.write(Path.of(image.getFilePath()), PNG); em.flush();
        assertThrows(IOException.class, () -> bundles.exportBundle(novel.getId()));
    }

    @Test void boundedReaderRejectsOversizedInput() {
        assertThrows(IOException.class, () -> BackupBundleService.readLimited(new ByteArrayInputStream(new byte[11]), 10));
    }
}
