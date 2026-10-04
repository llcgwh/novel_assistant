package com.novelwriting.service;

import static com.novelwriting.service.SeriesDocuments.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelwriting.config.WritingSyncConfig;
import com.novelwriting.controller.NovelSeriesController;
import com.novelwriting.entity.Novel;
import com.novelwriting.entity.WritingBook;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
@Import({com.novelwriting.security.TestCredentials.class, WritingService.class, SeriesService.class})
class SeriesControllerTest {
  @Autowired EntityManager em;
  @Autowired WritingService writing;
  @Autowired SeriesService series;

  private MockMvc mvc;
  private WritingCloudService cloud;

  /** Exposes the actual production registrations, so these tests cannot reproduce the exclusion incorrectly. */
  private static class CapturingRegistry extends InterceptorRegistry {
    HandlerInterceptor[] captured() {
      return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);
    }
  }

  @RestController
  static class ExtraSeriesController {
    @PostMapping("/api/novels/{novelId}/series-extra")
    Map<String, Boolean> extra(@PathVariable("novelId") Long novelId) {
      return Map.of("saved", true);
    }
  }

  @BeforeEach
  void installRealInterceptorWithObservableResourceMarking() {
    cloud = mock(WritingCloudService.class);
    doAnswer(call -> {
      Long novelId = call.getArgument(0);
      writing.touch(writing.book(novelId), false);
      return null;
    }).when(cloud).markResourceChange(anyLong());
    CapturingRegistry registry = new CapturingRegistry();
    new WritingSyncConfig(cloud).addInterceptors(registry);
    HandlerInterceptor[] interceptors = registry.captured();
    assertTrue(interceptors.length > 0, "the production sync interceptor must participate in every HTTP request");
    mvc = MockMvcBuilders.standaloneSetup(new NovelSeriesController(series), new ExtraSeriesController())
      .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
      .addInterceptors(interceptors).build();
  }

  private String uid() { return UUID.randomUUID().toString(); }

  private long novel() {
    Novel novel = new Novel(); novel.setTitle("系列 HTTP 同步边界");
    em.persist(novel); em.flush();
    return novel.getId();
  }

  private ObjectNode source() {
    ObjectNode payload = JSON.createObjectNode();
    for (String field : fields("location")) payload.put(field, field.equals("name") ? "母本地名" : "");
    payload.put("description", "第一行。\n保留中文与末尾空格。  ");
    ObjectNode request = JSON.createObjectNode().put("mutationId", uid()).put("templateUid", uid())
      .put("kind", "location").put("seriesName", "群星系列").put("authorStatus", "confirmed")
      .put("changeNote", "首版");
    request.set("payload", payload);
    return series.createTemplate(request);
  }

  private ObjectNode mutation(JsonNode state) {
    return JSON.createObjectNode().put("mutationId", uid()).put("epoch", state.path("epoch").asText())
      .put("expectedVersion", state.path("version").asLong());
  }

  private ObjectNode copyRequest(long novelId, JsonNode source) {
    ObjectNode state = series.get(novelId);
    return mutation(state).put("copyUid", uid()).put("templateUid", source.path("template").path("uid").asText())
      .put("revisionUid", source.path("revision").path("uid").asText())
      .put("universeUid", state.path("worlds").get(0).path("uid").asText()).put("planet", "青星");
  }

  private String base(long novelId) { return "/api/novels/" + novelId + "/series"; }

  private JsonNode request(MockHttpServletRequestBuilder request, JsonNode body, int expectedStatus) throws Exception {
    byte[] response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body)))
      .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsByteArray();
    return JSON.readTree(response);
  }

  private long sequence(long novelId) {
    // Observe the real persistent sequence, including any accidental afterCompletion touch.
    em.flush(); em.clear();
    return em.find(WritingBook.class, novelId).getChangeSequence();
  }

  @Test
  void httpCopyMarksTheNovelExactlyOnceInsideTheServiceTransaction() throws Exception {
    long id = novel(); ObjectNode input = copyRequest(id, source());
    long before = sequence(id);

    JsonNode result = request(post(base(id) + "/copies"), input, 200);

    assertFalse(result.path("replayed").asBoolean());
    assertEquals(1, result.path("state").path("copies").size());
    assertEquals(result.path("state").path("version").asLong(), result.path("resultVersion").asLong());
    assertEquals(before + 1, sequence(id), "a series mutation already touches its book; afterCompletion must not touch it again");
    verify(cloud, never()).markResourceChange(anyLong());
  }

  @Test
  void compareAndExactReplayAreReadOnlyAndReplayReturnsCurrentStateWithOriginalResultVersion() throws Exception {
    long id = novel(); ObjectNode source = source(), initial = copyRequest(id, source);
    JsonNode created = request(post(base(id) + "/copies"), initial, 200);
    String copyUid = created.path("state").path("copies").get(0).path("uid").asText();
    long afterCreate = sequence(id);

    JsonNode comparison = request(post(base(id) + "/copies/" + copyUid + "/compare"),
      JSON.createObjectNode().put("revisionUid", source.path("revision").path("uid").asText()), 200);
    assertEquals(copyUid, comparison.path("copyUid").asText());
    assertEquals(afterCreate, sequence(id), "POST compare is read-only and must not mark synchronization dirty");

    ObjectNode edit = mutation(created.path("state")).put("authorStatus", "draft").put("planet", "另星");
    ObjectNode content = created.path("state").path("copies").get(0).path("content").deepCopy();
    content.put("name", "仅本作修改的地名"); edit.set("content", content);
    JsonNode edited = request(put(base(id) + "/copies/" + copyUid), edit, 200);
    long afterEdit = sequence(id);
    assertEquals(afterCreate + 1, afterEdit);

    JsonNode replay = request(post(base(id) + "/copies"), initial, 200);

    assertTrue(replay.path("replayed").asBoolean());
    assertEquals(created.path("resultVersion").asLong(), replay.path("resultVersion").asLong());
    assertEquals(edited.path("state"), replay.path("state"), "an old receipt must return current content without reapplying its mutation");
    assertTrue(replay.path("state").path("version").asLong() > replay.path("resultVersion").asLong());
    assertEquals("仅本作修改的地名", replay.path("state").path("copies").get(0).path("content").path("name").asText());
    assertEquals(afterEdit, sequence(id), "a successful replay must not mark synchronization dirty");
    verify(cloud, never()).markResourceChange(anyLong());
  }

  @Test
  void failedVersionCheckReturns409WithoutChangingTheDocumentOrSyncSequence() throws Exception {
    long id = novel(); ObjectNode initial = copyRequest(id, source());
    JsonNode created = request(post(base(id) + "/copies"), initial, 200);
    String copyUid = created.path("state").path("copies").get(0).path("uid").asText();
    long before = sequence(id);
    ObjectNode stale = mutation(created.path("state")).put("expectedVersion", 0L).put("archived", true);

    JsonNode failure = request(post(base(id) + "/copies/" + copyUid + "/archive"), stale, 409);

    assertEquals("version_conflict", failure.path("code").asText());
    assertEquals(before, sequence(id));
    assertEquals(created.path("state").path("version").asLong(), series.get(id).path("version").asLong());
    assertFalse(series.get(id).path("copies").get(0).path("archived").asBoolean());
    verify(cloud, never()).markResourceChange(anyLong());
  }

  @Test
  void seriesPrefixOnAnUnrelatedResourceStillMarksSynchronizationDirty() throws Exception {
    long id = novel(); series.get(id);
    long before = sequence(id);

    JsonNode result = request(post("/api/novels/" + id + "/series-extra"), JSON.createObjectNode(), 200);

    assertTrue(result.path("saved").asBoolean());
    assertEquals(before + 1, sequence(id), "only the exact series path segment is exempt; series-extra remains a resource mutation");
    verify(cloud, times(1)).markResourceChange(id);
  }
}
