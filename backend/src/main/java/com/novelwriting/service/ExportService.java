package com.novelwriting.service;

import com.novelwriting.entity.*;
import com.novelwriting.entity.Character;
import com.novelwriting.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ExportService {

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    @Autowired
    private OutlineRepository outlineRepository;

    @Autowired
    private TimelineEventRepository timelineEventRepository;

    @Autowired
    private MapLocationRepository mapLocationRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private CharacterRelationshipRepository relationshipRepository;

    @Autowired
    private WorldviewEntryRepository worldviewEntryRepository;

    public byte[] exportNovelToJson(Long novelId) throws Exception {
        Map<String, Object> exportData = buildExportData(novelId);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper.writeValueAsBytes(exportData);
    }

    public byte[] exportNovelToMarkdown(Long novelId) throws Exception {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        writer.println("# " + novel.getTitle());
        writer.println();
        if (novel.getAuthor() != null) {
            writer.println("**作者:** " + novel.getAuthor());
        }
        if (novel.getGenre() != null) {
            writer.println("**类型:** " + novel.getGenre());
        }
        if (novel.getDescription() != null) {
            writer.println();
            writer.println("## 简介");
            writer.println(novel.getDescription());
        }
        writer.println();

        List<Character> characters = characterRepository.findByNovelId(novelId);
        if (!characters.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 人物设定");
            writer.println();
            for (Character c : characters) {
                writer.println("### " + c.getName());
                if (c.getRole() != null) {
                    writer.println("**角色定位:** " + c.getRole());
                }
                if (c.getDescription() != null) {
                    writer.println();
                    writer.println("**描述:** " + c.getDescription());
                }
                if (c.getPersonality() != null) {
                    writer.println();
                    writer.println("**性格:** " + c.getPersonality());
                }
                if (c.getAppearance() != null) {
                    writer.println();
                    writer.println("**外貌:** " + c.getAppearance());
                }
                if (c.getBackground() != null) {
                    writer.println();
                    writer.println("**背景故事:** " + c.getBackground());
                }
                writer.println();
            }
        }

        List<Scene> scenes = sceneRepository.findByNovelId(novelId);
        if (!scenes.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 场景设定");
            writer.println();
            for (Scene s : scenes) {
                writer.println("### " + s.getName());
                if (s.getLocation() != null) {
                    writer.println("**位置:** " + s.getLocation());
                }
                if (s.getDescription() != null) {
                    writer.println();
                    writer.println(s.getDescription());
                }
                if (s.getAtmosphere() != null) {
                    writer.println();
                    writer.println("**氛围:** " + s.getAtmosphere());
                }
                writer.println();
            }
        }

        List<Outline> outlines = outlineRepository.findByNovelIdOrderByPlotOrderAsc(novelId);
        if (!outlines.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 大纲");
            writer.println();
            for (Outline o : outlines) {
                String chapterInfo = o.getChapterNumber() != null ? "第" + o.getChapterNumber() + "章 " : "";
                writer.println("### " + chapterInfo + o.getTitle());
                if (o.getStatus() != null) {
                    String statusText = switch (o.getStatus()) {
                        case "planning" -> "规划中";
                        case "writing" -> "写作中";
                        case "completed" -> "已完成";
                        default -> o.getStatus();
                    };
                    writer.println("**状态:** " + statusText);
                }
                if (o.getContent() != null) {
                    writer.println();
                    writer.println(o.getContent());
                }
                writer.println();
            }
        }

        List<Foreshadow> foreshadows = foreshadowRepository.findByNovelId(novelId);
        if (!foreshadows.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 伏笔管理");
            writer.println();
            for (Foreshadow f : foreshadows) {
                writer.println("### " + f.getTitle());
                if (f.getStatus() != null) {
                    String statusText = switch (f.getStatus()) {
                        case "pending" -> "未揭示";
                        case "revealed" -> "已揭示";
                        case "abandoned" -> "已废弃";
                        default -> f.getStatus();
                    };
                    writer.println("**状态:** " + statusText);
                }
                if (f.getLaidAt() != null) {
                    writer.println("**埋下位置:** " + f.getLaidAt());
                }
                if (f.getRevealedAt() != null) {
                    writer.println("**揭示位置:** " + f.getRevealedAt());
                }
                if (f.getContent() != null) {
                    writer.println();
                    writer.println(f.getContent());
                }
                writer.println();
            }
        }

        List<TimelineEvent> events = timelineEventRepository.findByNovelIdOrderByRealOrderAsc(novelId);
        if (!events.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 时间轴");
            writer.println();
            for (TimelineEvent e : events) {
                writer.println("### " + e.getTitle());
                if (e.getEventTime() != null) {
                    writer.println("**时间:** " + e.getEventTime());
                }
                if (e.getDescription() != null) {
                    writer.println();
                    writer.println(e.getDescription());
                }
                writer.println();
            }
        }

        // Add worldview entries section
        List<WorldviewEntry> worldviewEntries = worldviewEntryRepository.findByNovelId(novelId);
        if (!worldviewEntries.isEmpty()) {
            writer.println("---");
            writer.println();
            writer.println("## 世界观设定");
            writer.println();

            // Group by category
            Map<String, List<WorldviewEntry>> grouped = new LinkedHashMap<>();
            for (WorldviewEntry entry : worldviewEntries) {
                grouped.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
            }

            Map<String, String> categoryLabels = new LinkedHashMap<>();
            categoryLabels.put("geography", "地理");
            categoryLabels.put("history", "历史");
            categoryLabels.put("culture", "文化");
            categoryLabels.put("magic_tech", "魔法/科技");
            categoryLabels.put("races", "种族");
            categoryLabels.put("politics", "政治");
            categoryLabels.put("religion", "宗教");
            categoryLabels.put("other", "其他");

            for (Map.Entry<String, List<WorldviewEntry>> group : grouped.entrySet()) {
                String categoryLabel = categoryLabels.getOrDefault(group.getKey(), group.getKey());
                writer.println("### " + categoryLabel);
                writer.println();
                for (WorldviewEntry entry : group.getValue()) {
                    writer.println("#### " + entry.getName());
                    if (entry.getContent() != null) {
                        writer.println();
                        writer.println(entry.getContent());
                    }
                    if (entry.getTags() != null && !entry.getTags().isEmpty()) {
                        writer.println();
                        writer.print("**标签:** ");
                        writer.println(entry.getTags().stream().map(Tag::getName).reduce((a, b) -> a + ", " + b).orElse(""));
                    }
                    writer.println();
                }
            }
        }

        writer.flush();
        return baos.toByteArray();
    }

    public byte[] exportWorldviewToMarkdown(Long novelId) throws Exception {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        List<WorldviewEntry> entries = worldviewEntryRepository.findByNovelId(novelId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        writer.println("# " + novel.getTitle() + " - 世界观设定集");
        writer.println();

        Map<String, List<WorldviewEntry>> grouped = new LinkedHashMap<>();
        Map<String, String> categoryLabels = new LinkedHashMap<>();
        categoryLabels.put("geography", "地理");
        categoryLabels.put("history", "历史");
        categoryLabels.put("culture", "文化");
        categoryLabels.put("magic_tech", "魔法/科技");
        categoryLabels.put("races", "种族");
        categoryLabels.put("politics", "政治");
        categoryLabels.put("religion", "宗教");
        categoryLabels.put("other", "其他");

        for (WorldviewEntry entry : entries) {
            grouped.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<String, List<WorldviewEntry>> group : grouped.entrySet()) {
            String categoryLabel = categoryLabels.getOrDefault(group.getKey(), group.getKey());
            writer.println("## " + categoryLabel);
            writer.println();
            for (WorldviewEntry entry : group.getValue()) {
                writer.println("### " + entry.getName());
                writer.println();
                if (entry.getContent() != null) {
                    writer.println(entry.getContent());
                    writer.println();
                }
                if (entry.getTags() != null && !entry.getTags().isEmpty()) {
                    writer.print("**标签:** ");
                    writer.println(entry.getTags().stream().map(Tag::getName).reduce((a, b) -> a + ", " + b).orElse(""));
                    writer.println();
                }
                writer.println("---");
                writer.println();
            }
        }

        writer.flush();
        return baos.toByteArray();
    }

    public byte[] exportCharactersToMarkdown(Long novelId) throws Exception {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        List<Character> characters = characterRepository.findByNovelId(novelId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        writer.println("# " + novel.getTitle() + " - 人物设定集");
        writer.println();

        for (Character c : characters) {
            writer.println("## " + c.getName());
            writer.println();
            if (c.getRole() != null) {
                writer.println("**角色定位:** " + c.getRole());
                writer.println();
            }
            if (c.getDescription() != null) {
                writer.println("### 描述");
                writer.println(c.getDescription());
                writer.println();
            }
            if (c.getPersonality() != null) {
                writer.println("### 性格");
                writer.println(c.getPersonality());
                writer.println();
            }
            if (c.getAppearance() != null) {
                writer.println("### 外貌");
                writer.println(c.getAppearance());
                writer.println();
            }
            if (c.getBackground() != null) {
                writer.println("### 背景故事");
                writer.println(c.getBackground());
                writer.println();
            }
            writer.println("---");
            writer.println();
        }

        writer.flush();
        return baos.toByteArray();
    }

    public byte[] exportOutlinesToMarkdown(Long novelId) throws Exception {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));

        List<Outline> outlines = outlineRepository.findByNovelIdOrderByPlotOrderAsc(novelId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        writer.println("# " + novel.getTitle() + " - 大纲");
        writer.println();

        for (Outline o : outlines) {
            String chapterInfo = o.getChapterNumber() != null ? "第" + o.getChapterNumber() + "章 " : "";
            writer.println("## " + chapterInfo + o.getTitle());
            writer.println();
            if (o.getStatus() != null) {
                String statusText = switch (o.getStatus()) {
                    case "planning" -> "规划中";
                    case "writing" -> "写作中";
                    case "completed" -> "已完成";
                    default -> o.getStatus();
                };
                writer.println("**状态:** " + statusText);
                writer.println();
            }
            if (o.getContent() != null) {
                writer.println(o.getContent());
                writer.println();
            }
            writer.println("---");
            writer.println();
        }

        writer.flush();
        return baos.toByteArray();
    }

    private Map<String, Object> buildExportData(Long novelId) {
        Map<String, Object> data = new LinkedHashMap<>();

        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new RuntimeException("Novel not found"));
        data.put("novel", novel);

        data.put("characters", characterRepository.findByNovelId(novelId));
        data.put("scenes", sceneRepository.findByNovelId(novelId));
        data.put("foreshadows", foreshadowRepository.findByNovelId(novelId));
        data.put("outlines", outlineRepository.findByNovelIdOrderByPlotOrderAsc(novelId));
        data.put("timelineEvents", timelineEventRepository.findByNovelIdOrderByRealOrderAsc(novelId));
        data.put("mapLocations", mapLocationRepository.findByNovelId(novelId));
        data.put("tags", tagRepository.findByNovelId(novelId));
        data.put("characterRelationships", relationshipRepository.findByNovelId(novelId));
        data.put("worldviewEntries", worldviewEntryRepository.findByNovelId(novelId));

        return data;
    }
}
