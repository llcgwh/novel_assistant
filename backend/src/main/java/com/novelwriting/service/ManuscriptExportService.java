package com.novelwriting.service;

import static com.novelwriting.service.WritingDocuments.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelwriting.entity.*;
import jakarta.persistence.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ManuscriptExportService {

  @Autowired
  private WritingService writing;

  @PersistenceContext
  private EntityManager em;

  public byte[] export(Long novelId, JsonNode options) throws IOException {
    Novel novel = em.find(Novel.class, novelId);
    if (novel == null) throw WritingService.missing();
    Set<String> selected = new HashSet<>();
    for (JsonNode id : options.path("uids")) selected.add(id.asText());
    Map<String, String> volumes = new LinkedHashMap<>();
    WritingBook book = em.find(WritingBook.class, novelId);
    if (book != null) for (JsonNode v : parse(book.getVolumes()))
      volumes.put(v.path("uid").asText(), v.path("title").asText());
    List<String> volumeOrder = new ArrayList<>(volumes.keySet());
    List<WritingChapter> all = writing
      .chapters(novelId)
      .stream()
      .filter(c -> !c.isDeleted())
      .sorted(
        Comparator.comparingInt((WritingChapter c) ->
          c.getVolumeId() == null ? -1 : volumeOrder.indexOf(c.getVolumeId())
        ).thenComparingInt(WritingChapter::getPosition)
      )
      .toList();
    Map<String, String> titles = new HashMap<>();
    int number = 0;
    for (WritingChapter c : all) {
      if (c.isNumbered()) number++;
      String title = c.getTitle();
      if (
        c.isNumbered() &&
        !title.matches("^第[零〇一二三四五六七八九十百千万两\\d]+[章节回].*")
      ) title = "第" + number + "章 " + title;
      titles.put(c.getUid(), title);
    }
    List<WritingChapter> chapters = all
      .stream()
      .filter(c -> selected.isEmpty() || selected.contains(c.getUid()))
      .toList();
    if (chapters.isEmpty()) throw WritingService.bad("没有可以导出的章节");
    if (
      !selected.isEmpty() && chapters.size() != selected.size()
    ) throw WritingService.bad("导出范围含无效章节");
    String format = options.path("format").asText("txt");
    if (format.equals("docx")) return docx(
      novel,
      chapters,
      volumes,
      titles,
      options
    );
    if (!Set.of("txt", "md").contains(format)) throw WritingService.bad(
      "不支持的导出格式"
    );
    StringBuilder out = new StringBuilder();
    boolean md = format.equals("md");
    out.append(md ? "# " : "").append(novel.getTitle()).append("\n");
    if (novel.getAuthor() != null) out.append(novel.getAuthor()).append("\n");
    out.append("\n");
    String previous = null;
    for (WritingChapter chapter : chapters) {
      if (
        chapter.getVolumeId() != null &&
        !Objects.equals(previous, chapter.getVolumeId())
      ) out
        .append(md ? "## " : "")
        .append(volumes.getOrDefault(chapter.getVolumeId(), "未命名篇卷"))
        .append("\n\n");
      previous = chapter.getVolumeId();
      out
        .append(md ? "### " : "")
        .append(titles.get(chapter.getUid()))
        .append("\n\n");
      out
        .append(
          md
            ? markdown(parse(chapter.getDocument()))
            : text(parse(chapter.getDocument()))
        )
        .append("\n\n");
      if (options.path("includeNotes").asBoolean()) {
        out
          .append(md ? "#### 创作笔记\n" : "【创作笔记】\n")
          .append(chapter.getNotes())
          .append("\n");
        for (JsonNode l : parse(chapter.getLinks()))
          out
            .append("- ")
            .append(l.path("type").asText())
            .append(" / ")
            .append(l.path("title").asText())
            .append(" / ")
            .append(l.path("role").asText())
            .append("\n");
      }
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private String markdown(JsonNode n) {
    String type = n.path("type").asText();
    if (type.equals("text")) {
      String s = n
        .path("text")
        .asText()
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[");
      for (JsonNode m : n.path("marks")) {
        String mark = m.path("type").asText();
        if (mark.equals("bold")) s = "**" + s + "**";
        if (mark.equals("italic")) s = "*" + s + "*";
        if (mark.equals("strike")) s = "~~" + s + "~~";
      }
      return s;
    }
    StringBuilder b = new StringBuilder();
    for (JsonNode child : n.path("content")) b.append(markdown(child));
    return switch (type) {
      case "paragraph" -> b + "\n\n";
      case "heading" -> "#".repeat(n.path("attrs").path("level").asInt(2)) +
      " " +
      b +
      "\n\n";
      case "hardBreak" -> "  \n";
      case "horizontalRule" -> "\n---\n\n";
      case "blockquote" -> "> " +
      b.toString().strip().replace("\n", "\n> ") +
      "\n\n";
      case "listItem" -> "- " + b.toString().strip() + "\n";
      default -> b.toString();
    };
  }

  private byte[] docx(
    Novel novel,
    List<WritingChapter> chapters,
    Map<String, String> volumes,
    Map<String, String> titles,
    JsonNode options
  ) throws IOException {
    StringBuilder body = new StringBuilder();
    if (options.path("titlePage").asBoolean(true)) {
      paragraph(body, novel.getTitle(), "Title", false);
      if (novel.getAuthor() != null) paragraph(
        body,
        novel.getAuthor(),
        "Subtitle",
        false
      );
    }
    if (options.path("toc").asBoolean()) {
      paragraph(
        body,
        "目录",
        "Title",
        options.path("titlePage").asBoolean(true)
      );
      body.append(
        "<w:p><w:fldSimple w:instr=\"TOC \\o &quot;1-2&quot; \\h \\z \\u\"><w:r><w:t>在 Word 中更新目录域以显示页码</w:t></w:r></w:fldSimple></w:p>"
      );
    }
    String previous = null;
    int index = 0;
    for (WritingChapter c : chapters) {
      boolean newVolume =
        c.getVolumeId() != null && !Objects.equals(previous, c.getVolumeId());
      boolean newPage =
        (index == 0 &&
          (options.path("titlePage").asBoolean(true) ||
            options.path("toc").asBoolean())) ||
        (index > 0 && options.path("pageBreak").asBoolean(true));
      if (newVolume) paragraph(
        body,
        volumes.getOrDefault(c.getVolumeId(), "篇卷"),
        "Heading1",
        newPage
      );
      previous = c.getVolumeId();
      paragraph(
        body,
        titles.get(c.getUid()),
        "Heading2",
        newPage && !newVolume
      );
      index++;
      renderBlocks(body, parse(c.getDocument()), false);
      if (options.path("includeNotes").asBoolean()) {
        paragraph(body, "创作笔记", "Heading3", false);
        paragraph(body, c.getNotes(), "Normal", false);
        for (JsonNode l : parse(c.getLinks()))
          paragraph(
            body,
            l.path("title").asText() + " · " + l.path("role").asText(),
            "Normal",
            false
          );
      }
    }
    body.append(
      "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
    );
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (
      ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)
    ) {
      entry(
        zip,
        "[Content_Types].xml",
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>"
      );
      entry(
        zip,
        "_rels/.rels",
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
      );
      entry(
        zip,
        "word/document.xml",
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
          body +
          "</w:body></w:document>"
      );
      entry(
        zip,
        "word/_rels/document.xml.rels",
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/><Relationship Id=\"settings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/></Relationships>"
      );
      StringBuilder styles = new StringBuilder(
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
      );
      styles.append(
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:pPr><w:ind w:firstLineChars=\"200\"/></w:pPr></w:style>"
      );
      for (int i = 1; i <= 3; i++) styles.append(
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading" +
          i +
          "\"><w:name w:val=\"heading " +
          i +
          "\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:ind w:firstLineChars=\"0\"/><w:outlineLvl w:val=\"" +
          (i - 1) +
          "\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"" +
          (36 - i * 4) +
          "\"/></w:rPr></w:style>"
      );
      for (String name : List.of("Title", "Subtitle"))
        styles.append(
          "<w:style w:type=\"paragraph\" w:styleId=\"" +
            name +
            "\"><w:name w:val=\"" +
            name +
            "\"/><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:sz w:val=\"" +
            (name.equals("Title") ? 44 : 28) +
            "\"/></w:rPr></w:style>"
        );
      entry(zip, "word/styles.xml", styles + "</w:styles>");
      entry(
        zip,
        "word/settings.xml",
        "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:updateFields w:val=\"true\"/></w:settings>"
      );
    }
    return bytes.toByteArray();
  }

  private void renderBlocks(StringBuilder out, JsonNode node, boolean bullet) {
    String type = node.path("type").asText();
    if (type.equals("paragraph") || type.equals("heading")) {
      out
        .append("<w:p><w:pPr><w:pStyle w:val=\"")
        .append(type.equals("heading") ? "Heading3" : "Normal")
        .append("\"/></w:pPr>");
      if (bullet) out.append(run("• ", null));
      renderRuns(out, node);
      out.append("</w:p>");
    } else if (type.equals("horizontalRule")) paragraph(
      out,
      "* * *",
      "Normal",
      false
    );
    else for (JsonNode child : node.path("content"))
      renderBlocks(out, child, bullet || type.equals("listItem"));
  }

  private void renderRuns(StringBuilder out, JsonNode node) {
    if (node.path("type").asText().equals("text")) out.append(
      run(node.path("text").asText(), node.path("marks"))
    );
    else if (node.path("type").asText().equals("hardBreak")) out.append(
      "<w:r><w:br/></w:r>"
    );
    else for (JsonNode child : node.path("content")) renderRuns(out, child);
  }

  private String run(String text, JsonNode marks) {
    StringBuilder props = new StringBuilder();
    if (marks != null) for (JsonNode m : marks)
      props.append(
        switch (m.path("type").asText()) {
          case "bold" -> "<w:b/>";
          case "italic" -> "<w:i/>";
          case "strike" -> "<w:strike/>";
          case "underline" -> "<w:u w:val=\"single\"/>";
          default -> "";
        }
      );
    return (
      "<w:r><w:rPr>" +
      props +
      "</w:rPr><w:t xml:space=\"preserve\">" +
      xml(text) +
      "</w:t></w:r>"
    );
  }

  private void paragraph(
    StringBuilder b,
    String text,
    String style,
    boolean page
  ) {
    b
      .append("<w:p><w:pPr><w:pStyle w:val=\"")
      .append(style)
      .append("\"/>")
      .append(page ? "<w:pageBreakBefore/>" : "")
      .append("</w:pPr>")
      .append(run(text == null ? "" : text, null))
      .append("</w:p>");
  }

  private static String xml(String text) {
    return text
      .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;");
  }

  private void entry(ZipOutputStream zip, String name, String content)
    throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(
      ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        content).getBytes(StandardCharsets.UTF_8)
    );
    zip.closeEntry();
  }
}
