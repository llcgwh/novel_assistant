package com.novelwriting.ai;

import static com.novelwriting.service.WritingDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiOutputPolicyTest {
  static ObjectNode directions() {
    ObjectNode root = JSON.createObjectNode();
    root.putArray("candidates"); root.putArray("issues");
    var rows = root.putArray("directions");
    for (int i = 0; i < 3; i++) {
      var row = rows.addObject().put("title", "方向" + i).put("goal", "推进承诺冲突")
        .put("choice", "让主角权衡身份与承诺").put("consequence", "可能增加失去信任的代价")
        .put("risk", "需要确认主角是否已知秘密").put("question", "主角愿意承担哪种代价？");
      row.putArray("sourceRefs").add("chapter:one"); row.putArray("assumptions");
    }
    return root;
  }
  static ObjectNode candidates() {
    ObjectNode root = JSON.createObjectNode(); root.putArray("directions"); root.putArray("issues");
    var rows = root.putArray("candidates");
    for (int i = 0; i < 3; i++) {
      var row = rows.addObject().put("title", "候选" + i).put("reason", "与现有命名体系相容");
      row.putObject("fields").put("name", "云" + i);
      row.putArray("sourceRefs").add("target-draft");
      row.putArray("assumptions").add("候选名字，尚待作者采用"); row.putArray("conflicts");
    }
    return root;
  }
  @Test void acceptsStructuredDirectionsWithoutMutationFields() {
    var output = directions();
    assertEquals(output, AiOutputPolicy.validate(AiActions.require("writing.inspiration"), output, Set.of("chapter:one")));
  }
  @Test void blocksProseDisguisedInAnyFieldAndBadReferences() {
    var output = directions();
    ((ObjectNode) output.path("directions").get(0)).put("question", "她低声说道：别走。");
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("writing.inspiration"), output, Set.of("chapter:one")));
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("writing.inspiration"), directions(), Set.of("chapter:other")));
  }
  @Test void blocksTargetFieldEscalationAndExtraOutput() {
    var output = candidates();
    ((ObjectNode) output.path("candidates").get(0).path("fields")).put("background", "额外改写");
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("character.name"), output, Set.of("target-draft")));
    var other = directions(); other.put("doc", "偷渡正文");
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("writing.inspiration"), other, Set.of("chapter:one")));
  }
  @Test void candidateAdditionsMustExplicitlyRemainAssumptions() {
    var output = candidates();
    ((ObjectNode) output.path("candidates").get(0)).putArray("assumptions");
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("character.name"), output, Set.of("target-draft")));
  }
  @Test void settingsAllowCreativeDescriptionsButWritingStillBlocksProse() {
    var output = candidates();
    for (var row : output.path("candidates")) {
      ((ObjectNode) row.path("fields")).remove("name");
      ((ObjectNode) row.path("fields")).put("personality", "习惯低声说道：稳住。他把冷静当作抵御恐惧的方式。");
    }
    assertDoesNotThrow(() -> AiOutputPolicy.validate(AiActions.require("character.personality"), output, Set.of("target-draft")));
    ((ObjectNode) output.path("candidates").get(0).path("fields")).put("personality", "<script>unsafe</script>");
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.validate(AiActions.require("character.personality"), output, Set.of("target-draft")));
  }
  @Test void semanticReviewReportsOnlyKnownCategoriesWithoutModelControlledReasons() {
    var failure = assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":false,\"issues\":[\"dialogue\",\"unmarked_fact\"]}")));
    assertTrue(failure.getReason().contains("对白")); assertTrue(failure.getReason().contains("既定事实"));
    for (String issues : java.util.List.of("[]", "[\"private model text\"]", "[\"dialogue\",\"dialogue\"]")) {
      var rejected = assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":false,\"issues\":" + issues + "}")));
      assertFalse(rejected.getReason().contains("private model text"));
    }
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":true,\"issues\":[\"dialogue\"]}")));
  }
  @Test void semanticReviewFailsClosedAndCannotReturnReplacementProse() {
    assertDoesNotThrow(() -> AiOutputPolicy.acceptReview(parse("{\"allowed\":true,\"issues\":[]}")));
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":false}")));
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":\"true\"}")));
    assertThrows(ResponseStatusException.class, () -> AiOutputPolicy.acceptReview(parse("{\"allowed\":true,\"text\":\"replacement\"}")));
  }
}
