package com.novelwriting.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared structure/source checks; only writing-workbench output has a prose boundary. */
public final class AiOutputPolicy {
  private static final Pattern NARRATIVE = Pattern.compile("(?:说道|问道|答道|喃喃|低声说|冷笑道|心想[：:]|我[，,]?[说问喊答][：:]|续写如下|正文如下|试写如下|例如[：:]?[“\"「])");
  private static final Pattern MARKUP = Pattern.compile("(?:```|<[/!]?[A-Za-z][^>]*>|https?://|data:)", Pattern.CASE_INSENSITIVE);

  public static ObjectNode validate(AiActions.Action action, JsonNode value, Set<String> sourceIds) {
    exact(value, Set.of("candidates", "directions", "issues"));
    if (value.toString().length() > 18000) throw rejected();
    JsonNode candidates = array(value, "candidates", 3);
    JsonNode directions = array(value, "directions", 3);
    JsonNode issues = array(value, "issues", 5);
    if (action.writing()) {
      if (directions.size() != 3 || !candidates.isEmpty() || !issues.isEmpty()) throw rejected();
    } else if (action.review()) {
      if (!directions.isEmpty() || !candidates.isEmpty()) throw rejected();
    } else if (candidates.size() != 3 || !directions.isEmpty() || !issues.isEmpty()) throw rejected();
    Set<String> titles = new HashSet<>();
    for (JsonNode candidate : candidates) {
      exact(candidate, Set.of("title", "reason", "fields", "sourceRefs", "assumptions", "conflicts"));
      uniqueTitle(candidate, titles);
      text(candidate, "reason", 350);
      exact(candidate.get("fields"), Set.of(action.field()));
      String field = text(candidate.get("fields"), action.field(), action.field().equals("name") ? 80 : 1200);
      if (action.field().equals("name") && (field.contains("\n") || field.contains("：") || field.contains(":"))) throw rejected();
      strings(candidate, "assumptions", 6, 250, true);
      strings(candidate, "conflicts", 6, 250, false);
      refs(candidate, sourceIds);
    }
    for (JsonNode direction : directions) {
      exact(direction, Set.of("title", "goal", "choice", "consequence", "risk", "question", "sourceRefs", "assumptions"));
      uniqueTitle(direction, titles);
      for (String field : List.of("goal", "choice", "consequence", "risk", "question")) {
        String content = text(direction, field, 240);
        // Original quotations are displayed by the UI from source links, never model-constructed.
        if (content.matches("(?s).*[“”「」『』\"].*")) throw rejected();
      }
      strings(direction, "assumptions", 6, 250, false);
      refs(direction, sourceIds);
    }
    for (JsonNode issue : issues) {
      exact(issue, Set.of("title", "detail", "sourceRefs"));
      uniqueTitle(issue, titles);
      text(issue, "detail", 500);
      refs(issue, sourceIds);
    }
    scan(value, action.writing());
    return ((ObjectNode) value).deepCopy();
  }

  static void exact(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size()) throw rejected("模型返回的字段数量或结构不符合本次任务");
    value.fieldNames().forEachRemaining(key -> { if (!expected.contains(key)) throw rejected("模型返回了本次任务以外的字段"); });
  }
  private static JsonNode array(JsonNode value, String key, int max) {
    JsonNode row = value.path(key);
    if (!row.isArray() || row.size() > max) throw rejected("模型返回的 " + key + " 列表格式或数量不符合要求");
    return row;
  }
  private static String text(JsonNode value, String key, int max) {
    JsonNode node = value.path(key);
    if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max) throw rejected("模型返回的 " + key + " 字段为空、格式有误或超过长度限制");
    return node.asText();
  }
  private static void uniqueTitle(JsonNode row, Set<String> titles) {
    if (!titles.add(text(row, "title", 80).trim())) throw rejected();
  }
  private static void strings(JsonNode value, String key, int max, int maxText, boolean required) {
    JsonNode list = array(value, key, max);
    if (required && list.isEmpty()) throw rejected();
    for (JsonNode node : list)
      if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > maxText) throw rejected();
  }
  private static void refs(JsonNode row, Set<String> sources) {
    JsonNode refs = array(row, "sourceRefs", 8);
    if (refs.isEmpty()) throw rejected("模型没有提供可核对的资料来源");
    Set<String> unique = new HashSet<>();
    for (JsonNode ref : refs)
      if (!ref.isTextual() || !sources.contains(ref.asText()) || !unique.add(ref.asText())) throw rejected("模型引用了未提供或重复的资料标识");
  }
  private static void scan(JsonNode value, boolean writing) {
    if (value.isTextual()) {
      String text = value.asText();
      if (writing && NARRATIVE.matcher(text).find()) throw rejected("写作工作台候选包含正文叙述或对白，请改为作者视角的目标、选择和问题");
      if (MARKUP.matcher(text).find() || text.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) throw rejected("模型候选包含链接或不支持的格式");
    } else value.forEach(child -> scan(child, writing));
  }
  private static final Map<String, String> REVIEW_ISSUES = Map.of(
    "narrative", "含可直接入稿的正文叙述", "dialogue", "含人物对白或示范句",
    "inner_monologue", "含人物内心独白", "scene_prose", "含场景描写成稿",
    "unmarked_fact", "将新增情节或设定当作既定事实", "uncertain_boundary", "无法确认内容仅为作者视角的创作方向");

  public static void acceptReview(JsonNode verdict) {
    exact(verdict, Set.of("allowed", "issues"));
    if (!verdict.path("allowed").isBoolean()) throw rejected("写作边界复核的 allowed 字段格式无效");
    JsonNode issues = array(verdict, "issues", 6);
    Set<String> seen = new HashSet<>();
    List<String> reasons = new ArrayList<>();
    for (JsonNode issue : issues) {
      if (!issue.isTextual() || !REVIEW_ISSUES.containsKey(issue.asText()) || !seen.add(issue.asText()))
        throw rejected("写作边界复核返回了无效的问题类别");
      reasons.add(REVIEW_ISSUES.get(issue.asText()));
    }
    if (verdict.path("allowed").asBoolean()) {
      if (!issues.isEmpty()) throw rejected("写作边界复核的结论与问题类别不一致");
    } else {
      if (issues.isEmpty()) throw rejected("写作边界复核未通过且没有提供问题类别");
      throw rejected("写作边界复核未通过：" + String.join("；", reasons));
    }
  }
  public static ResponseStatusException rejected() {
    return rejected("AI 返回内容未通过格式、引用或创作边界检查");
  }
  private static ResponseStatusException rejected(String detail) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail + "，未采用任何内容。可调整要求后重新尝试。"); }
  private AiOutputPolicy() {}
}
