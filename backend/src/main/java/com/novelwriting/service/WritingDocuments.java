package com.novelwriting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared canonical document and counting rules. No HTML is accepted or evaluated. */
public final class WritingDocuments {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> NODES=Set.of("doc","paragraph","heading","text","hardBreak","horizontalRule","blockquote","bulletList","orderedList","listItem");
    private static final Set<String> MARKS=Set.of("bold","italic","strike","underline","code");
    private static final Pattern WORDS=Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private WritingDocuments() {}
    public static JsonNode parse(String value) {
        try { return JSON.readTree(value); } catch(Exception e) { throw new IllegalArgumentException("无效的写作数据"); }
    }
    public static String stringify(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("无法保存写作数据"); }
    }
    public static ObjectNode empty() { return JSON.createObjectNode().put("type","doc").set("content",JSON.createArrayNode().add(JSON.createObjectNode().put("type","paragraph").set("attrs",JSON.createObjectNode().put("id",UUID.randomUUID().toString())))); }
    public static int count(String text) { return (int)WORDS.matcher(text.replaceAll("([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])"," $1 ")).results().count(); }
    public static String text(JsonNode node) {
        if(node.path("type").asText().equals("text")) return node.path("text").asText();
        if(node.path("type").asText().equals("hardBreak")) return "\n";
        StringBuilder result=new StringBuilder();
        for(JsonNode child:node.path("content")) {
            result.append(text(child));
            if(Set.of("paragraph","heading","horizontalRule").contains(child.path("type").asText())) result.append('\n');
        }
        return result.toString();
    }
    public static JsonNode validate(JsonNode input) {
        if(input==null || !input.isObject() || !input.path("type").asText().equals("doc") || input.toString().length()>2_000_000) throw new IllegalArgumentException("正文格式无效或超过单章 2 MB 限制");
        JsonNode doc=input.deepCopy();
        walk(doc,0,new HashSet<>(),new int[]{0});
        return doc;
    }
    private static void walk(JsonNode node,int depth,Set<String> ids,int[] total) {
        if(depth>24 || ++total[0]>50000 || !node.isObject() || !NODES.contains(node.path("type").asText())) throw new IllegalArgumentException("不支持的正文结构");
        String type=node.path("type").asText();
        if(type.equals("text") && !node.path("text").isTextual()) throw new IllegalArgumentException("正文文本无效");
        for(JsonNode mark:node.path("marks")) if(!MARKS.contains(mark.path("type").asText())) throw new IllegalArgumentException("不支持的文字格式");
        if(Set.of("paragraph","heading","blockquote","horizontalRule").contains(type)) {
            ObjectNode attrs=((ObjectNode)node).withObject("attrs");
            String id=attrs.path("id").asText();
            if(id.isBlank()) { id=UUID.randomUUID().toString(); attrs.put("id",id); }
            if(id.length()>80 || !ids.add(id)) throw new IllegalArgumentException("段落标识重复或无效");
        }
        if(type.equals("heading") && (node.path("attrs").path("level").asInt()<1 || node.path("attrs").path("level").asInt()>3)) throw new IllegalArgumentException("标题层级无效");
        if(node.has("content") && !node.path("content").isArray()) throw new IllegalArgumentException("段落内容无效");
        for(JsonNode child:node.path("content")) walk(child,depth+1,ids,total);
    }
    public static Set<String> blocks(JsonNode doc) { Set<String> ids=new HashSet<>(); collect(doc,ids); return ids; }
    private static void collect(JsonNode n,Set<String> ids) { if(n.path("attrs").hasNonNull("id")) ids.add(n.path("attrs").path("id").asText()); for(JsonNode c:n.path("content")) collect(c,ids); }
}
