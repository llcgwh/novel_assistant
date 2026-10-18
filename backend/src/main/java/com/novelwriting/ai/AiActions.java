package com.novelwriting.ai;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Server-owned capabilities. Neither a document nor a caller can select arbitrary fields or tools. */
public final class AiActions {
  public record Action(String resource, String field, String mode, String instruction) {
    public boolean writing() { return mode.equals("writing"); }
    public boolean review() { return mode.equals("review"); }
  }

  private static final Map<String, Action> ACTIONS = Map.ofEntries(
    Map.entry("character.name", new Action("characters", "name", "candidate", "提出三个人名候选，保持时代、文化、音韵和既有人名的辨识度。除非作者明确要同名，否则避开本作已有姓名及别名；理由说明区别，不顺带创造身份经历。")),
    Map.entry("character.personality", new Action("characters", "personality", "candidate", "提出三个性格档案候选，兼顾欲望、矛盾、缺点和行为倾向；围绕指定性格字段进行创作。")),
    Map.entry("character.background", new Action("characters", "background", "candidate", "提出三个经历档案候选，说明经历与现有人格动机的因果；新增经历必须列为假设。")),
    Map.entry("character.appearance", new Action("characters", "appearance", "candidate", "提出三个外貌设定候选，描绘人物特征，不以身份推定性格。")),
    Map.entry("location.name", new Action("map", "name", "candidate", "提出三个地点或地标名称候选，参考所属地区、层级、用途及命名体系。")),
    Map.entry("location.description", new Action("map", "description", "candidate", "提出三个地点资料描述候选，涉及用途、环境、来历；不改坐标、父级和已有关系。")),
    Map.entry("scene.name", new Action("scenes", "name", "candidate", "提出三个场景名称候选，与已有关联地点和氛围相符。")),
    Map.entry("scene.description", new Action("scenes", "description", "candidate", "提出三个场景描述候选，提供环境、氛围与情节作用，只生成指定描述字段。")),
    Map.entry("worldview.content", new Action("worldview", "content", "candidate", "提出三个世界设定条目候选，说明规则、限制与代价，新增规则只是假设。")),
    Map.entry("outline.review", new Action("outlines", "", "review", "审视大纲的动机、因果、冲突与未决问题；未来计划不是已发生事实。")),
    Map.entry("timeline.review", new Action("timeline", "", "review", "审视事件的故事顺序、因果与叙述顺序差异，不把时间标签臆造为精确日期。")),
    Map.entry("foreshadow.review", new Action("foreshadows", "", "review", "审视线索的埋设、误导、回收条件；计划不等于正文已完成，提出检查问题。")),
    Map.entry("relationship.review", new Action("relationships", "", "review", "审视关系的动机、双向期待和矛盾；不擅自确认关系变化，提出检查问题。")),
    Map.entry("writing.inspiration", new Action("", "", "writing", "在当前章节末尾提出三个不同的下一步方向；只讨论目标、人物选择、因果、代价和待作者决定的问题。"))
  );

  public static Action require(String name) {
    Action result = ACTIONS.get(name);
    if (result == null) throw invalid("不支持的 AI 功能");
    return result;
  }
  public static Set<String> names() { return ACTIONS.keySet(); }
  public static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
  private AiActions() {}
}
