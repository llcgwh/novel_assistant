package com.novelwriting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.CharacterRepository;
import com.novelwriting.repository.ImageRepository;
import com.novelwriting.repository.NovelRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises C08 through the same complete backup transaction used by HTTP and cloud pull. */
@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, SeriesService.class, BackupBundleService.class,
        ImportService.class, ExportService.class, WritingService.class})
class SeriesBackupTest {
    static final Path ROOT = temporaryDirectory();
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.upload.dir", ROOT::toString); }
    @Autowired EntityManager em;
    @Autowired SeriesService series;
    @Autowired BackupBundleService bundles;
    @Autowired ImportService importer;
    @Autowired ExportService exporter;
    @Autowired WritingService writing;
    @SpyBean ImageRepository images;
    @SpyBean CharacterRepository characters;
    @SpyBean NovelRepository novels;
    final ObjectMapper json = new ObjectMapper();

    static Path temporaryDirectory() {
        try { return Files.createTempDirectory("novel-series-backup-test-"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @AfterAll static void removeTemporaryImages() throws IOException {
        try (var files = Files.walk(ROOT)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    Novel novel(String title) { Novel n = new Novel(); n.setTitle(title); em.persist(n); return n; }
    String uid() { return UUID.randomUUID().toString(); }
    ObjectNode object() { return json.createObjectNode(); }
    ObjectNode read(byte[] bytes) throws Exception { return (ObjectNode) json.readTree(bytes); }
    Image image(Novel novel) throws IOException {
        Path directory = ROOT.resolve(novel.getId().toString()); Files.createDirectories(directory);
        Path file = directory.resolve(uid() + ".png"); Files.write(file, PNG);
        Image row = new Image(); row.setNovelId(novel.getId()); row.setFilename(file.getFileName().toString());
        row.setFilePath(file.toString()); row.setOriginalName("封面.png"); row.setMimeType("image/png");
        row.setImageType("novel_cover"); row.setFileSize(PNG.length); em.persist(row); return row;
    }
    Character character(Novel novel) {
        Character row = new Character(); row.setNovelId(novel.getId()); row.setName("原有角色"); em.persist(row); return row;
    }
    ObjectNode payload(String kind, String label) {
        ObjectNode out = object();
        for (String key : SeriesDocuments.fields(kind)) out.put(key, key.equals("name") ? label : "  " + label + " / " + key + "\n保留换行。\n");
        return out;
    }
    ObjectNode template(String kind, String label) {
        ObjectNode input = object().put("mutationId", uid()).put("templateUid", uid()).put("kind", kind)
                .put("seriesName", "星海系列").put("authorStatus", "confirmed").put("changeNote", "首版");
        input.set("payload", payload(kind, label)); return series.createTemplate(input);
    }
    ObjectNode publish(ObjectNode previous, String label) {
        JsonNode metadata = series.template(previous.path("template").path("uid").asText()).path("template");
        ObjectNode input = object().put("mutationId", uid()).put("expectedLockVersion", metadata.path("lockVersion").asLong())
                .put("expectedHeadRevisionUid", metadata.path("headRevisionUid").asText()).put("seriesName", "星海系列")
                .put("authorStatus", "confirmed").put("changeNote", label);
        input.set("payload", payload(metadata.path("kind").asText(), label));
        return series.publish(metadata.path("uid").asText(), input);
    }
    ObjectNode command(Long id) {
        ObjectNode state = series.get(id);
        return object().put("mutationId", uid()).put("epoch", state.path("epoch").asText())
                .put("expectedVersion", state.path("version").asLong());
    }
    ObjectNode copy(Novel novel, ObjectNode source) {
        ObjectNode input = command(novel.getId()).put("copyUid", uid())
                .put("universeUid", series.get(novel.getId()).path("worlds").get(0).path("uid").asText())
                .put("planet", "蓝星").put("templateUid", source.path("template").path("uid").asText())
                .put("revisionUid", source.path("revision").path("uid").asText());
        return (ObjectNode) series.createCopy(novel.getId(), input).path("state").path("copies").get(0);
    }
    void assertOriginal(Novel novel, Image image, Character character, ObjectNode state) throws IOException {
        assertEquals(state, series.get(novel.getId()));
        assertEquals(image.getId(), images.findByNovelId(novel.getId()).get(0).getId());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(image.getFilePath())));
        assertEquals(character.getId(), characters.findByNovelId(novel.getId()).get(0).getId());
        verify(images, never()).deleteAll(any(Iterable.class));
        verify(characters, never()).deleteByNovelId(novel.getId());
        try (var files = Files.list(ROOT.resolve(novel.getId().toString()))) { assertEquals(1, files.count()); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void firstExportsPersistOneDefaultWorldAndActualWritingIdentity(boolean hasBook) throws Exception {
        Novel n = novel("旧作品首次导出");
        if (hasBook) assertNull(writing.book(n.getId()).getSeriesData());
        else assertNull(em.find(WritingBook.class, n.getId()));
        ObjectNode first = read(exporter.exportNovelToJson(n.getId()));
        ObjectNode second = read(bundles.exportBundle(n.getId()));
        ObjectNode state = series.get(n.getId());
        assertEquals(4, first.path("schemaVersion").asInt());
        assertEquals(first.path("series").path("document").path("worlds"), second.path("data").path("series").path("document").path("worlds"));
        assertEquals(state.path("worlds"), first.path("series").path("document").path("worlds"));
        assertFalse(first.path("series").path("document").has("epoch"));
        assertEquals(writing.book(n.getId()).getUid(), first.path("writing").path("uid").asText());
        assertEquals(first.path("writing").path("uid"), second.path("data").path("writing").path("uid"));
        assertEquals(0, writing.book(n.getId()).getChangeSequence(), "Initializing an empty state is not an author edit");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedFirstImageExportRollsBackBookAndSeriesInitialization(boolean hasBook) throws Exception {
        Novel n = novel("缺失图片"); Image cover = image(n); Files.delete(Path.of(cover.getFilePath()));
        String originalUid = hasBook ? writing.book(n.getId()).getUid() : null;
        em.flush(); TestTransaction.flagForCommit(); TestTransaction.end();
        assertThrows(IOException.class, () -> bundles.exportBundle(n.getId()));
        TestTransaction.start(); em.clear();
        WritingBook book = em.find(WritingBook.class, n.getId());
        if (hasBook) { assertNotNull(book); assertEquals(originalUid, book.getUid()); assertNull(book.getSeriesData()); }
        else assertNull(book);
        assertNotNull(em.find(Image.class, cover.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "null"})
    void schemaFourAlwaysRequiresCompleteSeriesBeforeAnyImageOrNativeDeletion(String mode) throws Exception {
        Novel n = novel("保护 schema 4"); Image cover = image(n); Character hero = character(n);
        ObjectNode backup = read(bundles.exportBundle(n.getId())); ObjectNode state = series.get(n.getId());
        ObjectNode data = (ObjectNode) backup.path("data");
        if (mode.equals("missing")) data.remove("series"); else data.putNull("series");
        clearInvocations(images, characters);
        SeriesProblem problem = assertThrows(SeriesProblem.class, () -> bundles.restore(n.getId(), json.writeValueAsBytes(backup)));
        assertEquals(400, problem.getStatusCode().value());
        assertThrows(SeriesProblem.class, () -> importer.importFromJson(n.getId(), json.writeValueAsBytes(data)));
        assertOriginal(n, cover, hero, state);
    }

    @ParameterizedTest @ValueSource(strings = {"copy", "worldName", "worldDescription", "extraWorld"})
    void oldPackagesCannotEraseAnyMeaningfulSeriesData(String kind) throws Exception {
        Novel n = novel("保护旧包"); Image cover = image(n); Character hero = character(n);
        ObjectNode old = read(bundles.exportBundle(n.getId()));
        ((ObjectNode) old.path("data")).put("schemaVersion", 3).remove("series");
        String world = series.get(n.getId()).path("worlds").get(0).path("uid").asText();
        if (kind.equals("copy")) copy(n, template("character", "保持完整人物"));
        else if (kind.equals("extraWorld")) series.world(n.getId(), "", "create", command(n.getId()).put("worldUid", uid()).put("name", "另一世界").put("description", ""));
        else series.world(n.getId(), world, "edit", command(n.getId()).put("name", kind.equals("worldName") ? "作者世界" : "本作世界")
                .put("description", kind.equals("worldDescription") ? "作者的独立世界说明" : ""));
        ObjectNode state = series.get(n.getId()); clearInvocations(images, characters);
        SeriesProblem problem = assertThrows(SeriesProblem.class, () -> bundles.restore(n.getId(), json.writeValueAsBytes(old)));
        assertEquals(409, problem.getStatusCode().value()); assertEquals("legacy_series_missing", problem.getCode());
        assertThrows(SeriesProblem.class, () -> importer.importFromJson(n.getId(), json.writeValueAsBytes(old.path("data"))));
        long novelsBefore = em.createQuery("select count(n) from Novel n", Long.class).getSingleResult();
        assertThrows(SeriesProblem.class, () -> bundles.restoreWithCopy(n.getId(), json.writeValueAsBytes(old)));
        assertEquals(novelsBefore, em.createQuery("select count(n) from Novel n", Long.class).getSingleResult());
        assertOriginal(n, cover, hero, state);
    }

    @Test void legacyRestoreAllowsSemanticallyEmptyStateDespitePriorVersionAndRotatesEpoch() throws Exception {
        Novel n = novel("仍为空设定"); ObjectNode initial = series.get(n.getId());
        String world = initial.path("worlds").get(0).path("uid").asText();
        series.world(n.getId(), world, "edit", command(n.getId()).put("name", "本作世界").put("description", ""));
        ObjectNode before = series.get(n.getId()); assertEquals(1, before.path("version").asLong());
        ObjectNode old = read(exporter.exportNovelToJson(n.getId())); old.put("schemaVersion", 3).remove("series");
        importer.importFromJson(n.getId(), json.writeValueAsBytes(old));
        ObjectNode after = series.get(n.getId());
        assertEquals(2, after.path("version").asLong()); assertNotEquals(before.path("epoch"), after.path("epoch"));
        assertTrue(after.path("copies").isEmpty()); assertEquals("本作世界", after.path("worlds").get(0).path("name").asText());
    }

    @Test void completeFiveKindsRestoreIndependentlyWithPinnedSourcesAndMonotonicVersion() throws Exception {
        Novel source = novel("五类完整源作品"), target = novel("目标"), other = novel("其他作品");
        ObjectNode latest = null;
        for (String kind : List.of("calendar", "location", "race", "organization", "character")) {
            latest = template(kind, "源 " + kind); copy(source, latest);
        }
        ObjectNode later = publish(latest, "本作尚未采用的新母本");
        String templateUid = later.path("template").path("uid").asText();
        series.archiveTemplate(templateUid, object().put("mutationId", uid()).put("expectedLockVersion", later.path("template").path("lockVersion").asLong()).put("archived", true));
        JsonNode headBefore = series.template(templateUid).path("template");
        ObjectNode otherBefore = series.get(other.getId()); ObjectNode targetBefore = series.get(target.getId());
        byte[] backup = bundles.exportBundle(source.getId()); JsonNode data = read(backup).path("data").path("series");
        assertEquals(5, data.path("library").path("revisions").size());
        assertEquals(2, series.exportLibrary(object().set("templateUids", json.createArrayNode().add(templateUid))).path("revisions").size());
        bundles.restore(target.getId(), backup);
        ObjectNode sourceState = series.get(source.getId()), restored = series.get(target.getId());
        assertEquals(sourceState.path("copies"), restored.path("copies"));
        assertEquals(sourceState.path("worlds"), restored.path("worlds"));
        assertEquals(sourceState.path("sourceRevisions"), restored.path("sourceRevisions"));
        assertEquals(Math.max(sourceState.path("version").asLong(), targetBefore.path("version").asLong()) + 1, restored.path("version").asLong());
        assertNotEquals(targetBefore.path("epoch"), restored.path("epoch"));
        assertEquals(headBefore, series.template(templateUid).path("template")); assertEquals(otherBefore, series.get(other.getId()));
        ObjectNode localCopy = (ObjectNode) restored.path("copies").get(0); ObjectNode edited = ((ObjectNode) localCopy.path("content")).deepCopy().put("description", "只改目标作品");
        ObjectNode edit = command(target.getId()).put("planet", "目标星").put("authorStatus", "draft"); edit.set("content", edited);
        series.editCopy(target.getId(), localCopy.path("uid").asText(), edit);
        assertEquals(sourceState, series.get(source.getId()));
    }

    @Test void reviewAndRestoredHistoryKeepEveryReferencedSourceInBackupClosure() throws Exception {
        Novel n = novel("来源闭包"); ObjectNode v1 = template("character", "第一版"); ObjectNode original = copy(n, v1);
        String copyUid = original.path("uid").asText(); ObjectNode edit = command(n.getId()).put("planet", "蓝星").put("authorStatus", "confirmed");
        edit.set("content", ((ObjectNode) original.path("content")).deepCopy().put("notes", "作者本地专属备注")); series.editCopy(n.getId(), copyUid, edit);
        ObjectNode v2 = publish(v1, "第二版"); adopt(n, copyUid, v2, false);
        ObjectNode v3 = publish(v2, "第三版"); adopt(n, copyUid, v3, true);
        String historyUid = series.get(n.getId()).path("copies").get(0).path("history").get(0).path("uid").asText();
        series.restoreCopy(n.getId(), copyUid, command(n.getId()).put("historyUid", historyUid));
        JsonNode section = read(exporter.exportNovelToJson(n.getId())).path("series");
        Set<String> expected = Set.of(v1.path("revision").path("uid").asText(), v2.path("revision").path("uid").asText(), v3.path("revision").path("uid").asText());
        Set<String> actual = new HashSet<>(); section.path("library").path("revisions").forEach(row -> actual.add(row.path("uid").asText()));
        assertEquals(expected, actual);
        Novel target = novel("闭包恢复"); bundles.restore(target.getId(), bundles.exportBundle(n.getId()));
        assertEquals(series.get(n.getId()).path("copies"), series.get(target.getId()).path("copies"));
        assertEquals(series.get(n.getId()).path("sourceRevisions"), series.get(target.getId()).path("sourceRevisions"));
    }
    void adopt(Novel n, String copyUid, ObjectNode version, boolean reviewOnly) {
        ObjectNode comparison = series.compare(n.getId(), copyUid, object().put("revisionUid", version.path("revision").path("uid").asText()));
        ObjectNode input = command(n.getId()).put("revisionUid", version.path("revision").path("uid").asText())
                .put("copyHash", comparison.path("copyHash").asText()).put("baselineRevisionUid", comparison.path("baselineRevisionUid").asText());
        if (!reviewOnly) input.putArray("selectedFields").add("description");
        series.adopt(n.getId(), copyUid, input, reviewOnly);
    }

    @ParameterizedTest @ValueSource(strings = {"hash", "world", "unknownSource", "kind", "identity"})
    void malformedOrConflictingSourcesNeverReachImageOrNativeDeletion(String mode) throws Exception {
        Novel n = novel("先校验完整来源"); Image cover = image(n); Character hero = character(n);
        copy(n, template("character", "不丢设定"));
        ObjectNode backup = read(bundles.exportBundle(n.getId())); ObjectNode state = series.get(n.getId());
        ObjectNode section = (ObjectNode) backup.path("data").path("series");
        ObjectNode copy = (ObjectNode) section.path("document").path("copies").get(0);
        if (mode.equals("world")) copy.put("universeUid", uid());
        else if (mode.equals("unknownSource")) copy.put("baselineRevisionUid", uid());
        else if (mode.equals("kind")) copy.put("kind", "location");
        else {
            for (JsonNode revisions : List.of(section.path("document").path("sourceRevisions"), section.path("library").path("revisions"))) {
                ObjectNode revision = (ObjectNode) revisions.get(0);
                if (mode.equals("hash")) revision.put("hash", "0".repeat(64));
                else { revision.put("changeNote", "同身份不同的重签内容"); revision.put("hash", SeriesDocuments.revisionHash(revision)); }
            }
        }
        clearInvocations(images, characters);
        SeriesProblem problem = assertThrows(SeriesProblem.class, () -> bundles.restore(n.getId(), json.writeValueAsBytes(backup)));
        assertEquals(mode.equals("identity") ? 409 : 400, problem.getStatusCode().value());
        if (mode.equals("identity")) assertEquals("source_identity", problem.getCode());
        assertOriginal(n, cover, hero, state);
    }

    @ParameterizedTest @ValueSource(strings = {"incoming", "current"})
    void exhaustedRestoreVersionIsRejectedBeforeImagesOrNativeDataChange(String counter) throws Exception {
        Novel n = novel("恢复计数上限"); Image cover = image(n); Character hero = character(n);
        ObjectNode backup = read(bundles.exportBundle(n.getId()));
        if (counter.equals("incoming")) ((ObjectNode) backup.path("data").path("series").path("document")).put("version", SeriesDocuments.MAX_COUNTER);
        else {
            ObjectNode exhausted = series.get(n.getId()).put("version", SeriesDocuments.MAX_COUNTER);
            writing.book(n.getId()).setSeriesData(json.writeValueAsString(exhausted));
        }
        ObjectNode before = series.get(n.getId()); clearInvocations(images, characters);
        SeriesProblem error = assertThrows(SeriesProblem.class, () -> bundles.restore(n.getId(), json.writeValueAsBytes(backup)));
        assertEquals(413, error.getStatusCode().value());
        assertOriginal(n, cover, hero, before);
    }

    @Test void restoreEpochOverheadIsPreflightedBeforeChangingImagesOrNativeRecords() throws Exception {
        Novel n = novel("恢复容量上限"); Image cover = image(n); Character hero = character(n);
        copy(n, template("calendar", "容量来源")); ObjectNode before = series.get(n.getId());
        ObjectNode backup = read(bundles.exportBundle(n.getId()));
        ObjectNode section = (ObjectNode) backup.path("data").path("series"), document = (ObjectNode) section.path("document");
        ObjectNode prototype = ((ObjectNode) document.path("copies").get(0)).deepCopy();
        ((ObjectNode) prototype.path("content")).put("notes", "A".repeat(20_000));
        ((ObjectNode) prototype.path("fieldOrigins")).putObject("notes").put("kind", "local").putNull("revisionUid");
        var copies = document.putArray("copies");
        int size = json.writeValueAsBytes(document).length;
        int rowSize = json.writeValueAsBytes(prototype).length;
        int desired = SeriesDocuments.MAX_STATE_BYTES - 20;
        while (size + rowSize + (copies.isEmpty() ? 0 : 1) <= desired) {
            copies.add(prototype.deepCopy().put("uid", uid()));
            size += rowSize + (copies.size() == 1 ? 0 : 1);
        }
        ObjectNode last = prototype.deepCopy().put("uid", uid());
        ((ObjectNode) last.path("content")).put("notes", "");
        int emptyRowSize = json.writeValueAsBytes(last).length;
        int remaining = desired - size - emptyRowSize - 1;
        // If the remaining space cannot hold another complete row, shorten the previous notes first.
        if (remaining < 0) {
            ObjectNode previous = (ObjectNode) copies.get(copies.size() - 1);
            ((ObjectNode) previous.path("content")).put("notes", "A".repeat(20_000 + remaining));
            remaining = 0;
        }
        ((ObjectNode) last.path("content")).put("notes", "A".repeat(remaining)); copies.add(last);
        assertEquals(desired, json.writeValueAsBytes(document).length);
        SeriesDocuments.validateBackup(section); // The portable document itself is valid and below its limit.
        clearInvocations(images, characters);
        SeriesProblem error = assertThrows(SeriesProblem.class, () -> bundles.restore(n.getId(), json.writeValueAsBytes(backup)));
        assertEquals(413, error.getStatusCode().value());
        assertOriginal(n, cover, hero, before);
    }

    @Test void recoveryCopyPreservesFullSeriesAndRestoreInvalidatesOldSuccessfulReceipts() throws Exception {
        Novel n = novel("有回收历史"); image(n); ObjectNode first = template("race", "本机种族"); copy(n, first);
        ObjectNode saved = series.get(n.getId()); String world = saved.path("worlds").get(0).path("uid").asText();
        ObjectNode mutation = command(n.getId()).put("name", "作者本机世界").put("description", "恢复前应完整保留");
        series.world(n.getId(), world, "edit", mutation); ObjectNode before = series.get(n.getId());
        Novel incoming = novel("要恢复的作品"); copy(incoming, template("location", "远端地点"));
        Long recovery = bundles.restoreWithCopy(n.getId(), bundles.exportBundle(incoming.getId()));
        ObjectNode recovered = series.get(recovery);
        assertEquals(before.path("copies"), recovered.path("copies")); assertEquals(before.path("worlds"), recovered.path("worlds"));
        assertEquals(before.path("sourceRevisions"), recovered.path("sourceRevisions")); assertEquals(1, images.findByNovelId(recovery).size());
        assertTrue(em.find(Novel.class, recovery).getTitle().contains("恢复前副本"));
        ObjectNode after = series.get(n.getId()); assertNotEquals(before.path("epoch"), after.path("epoch"));
        SeriesProblem stale = assertThrows(SeriesProblem.class, () -> series.world(n.getId(), world, "edit", mutation));
        assertEquals("epoch_conflict", stale.getCode()); assertEquals(after, series.get(n.getId()));
    }

    @Test void failedFinalNativeSaveRollsBackMergedSourcesStateAndNewImages() throws Exception {
        Novel source = novel("离线来源"); image(source); ObjectNode created = template("organization", "只在包内的组织"); copy(source, created);
        byte[] backup = bundles.exportBundle(source.getId());
        String templateUid = created.path("template").path("uid").asText(), revisionUid = created.path("revision").path("uid").asText();
        em.remove(em.find(SeriesTemplateRevision.class, revisionUid)); em.remove(em.find(SeriesTemplate.class, templateUid));
        Novel target = novel("回滚目标"); Image cover = image(target); Character hero = character(target); ObjectNode before = series.get(target.getId());
        em.flush(); TestTransaction.flagForCommit(); TestTransaction.end();
        doThrow(new IllegalStateException("simulated final native save failure")).when(novels).save(argThat(n -> n != null && n.getId().equals(target.getId())));
        assertThrows(IllegalStateException.class, () -> bundles.restore(target.getId(), backup));
        TestTransaction.start(); em.clear();
        assertNull(em.find(SeriesTemplate.class, templateUid)); assertNull(em.find(SeriesTemplateRevision.class, revisionUid));
        assertEquals(before, series.get(target.getId())); assertNotNull(em.find(Character.class, hero.getId()));
        assertEquals(cover.getId(), images.findByNovelId(target.getId()).get(0).getId());
        assertArrayEquals(PNG, Files.readAllBytes(Path.of(cover.getFilePath())));
        try (var files = Files.list(ROOT.resolve(target.getId().toString()))) { assertEquals(1, files.count()); }
    }
}
