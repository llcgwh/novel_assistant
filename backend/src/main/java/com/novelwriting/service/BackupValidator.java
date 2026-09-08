package com.novelwriting.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validate the entire reference graph before destructive replacement starts. */
final class BackupValidator {
    private static final List<String> REQUIRED = List.of("tags", "characters", "scenes",
            "foreshadows", "outlines", "timelineEvents", "mapLocations", "characterRelationships");
    private static final List<String> OPTIONAL = List.of("worldviewEntries", "relationshipGroups");

    static void validate(JsonNode root) {
        require(root != null && root.isObject(), "Backup must be an object");
        require(root.path("novel").isObject(), "Missing novel metadata");
        require(root.path("novel").path("title").isTextual(), "Missing novel title");
        int version = 1;
        if (root.has("schemaVersion")) {
            require(root.get("schemaVersion").isIntegralNumber(), "Invalid schemaVersion");
            require(root.get("schemaVersion").canConvertToInt(), "Invalid schemaVersion");
            version = root.get("schemaVersion").asInt();
            require(version == 1 || version == 2, "Unsupported backup version");
        }
        List<String> sections = new ArrayList<>(REQUIRED);
        sections.addAll(OPTIONAL);
        Map<String, Set<Long>> ids = new HashMap<>();
        for (String section : sections) {
            JsonNode rows = root.path(section);
            boolean required = REQUIRED.contains(section) || version == 2;
            require(!rows.isMissingNode() || !required, "Missing section: " + section);
            require(rows.isMissingNode() || rows.isArray(), "Invalid section: " + section);
            Set<Long> seen = new HashSet<>();
            for (JsonNode row : rows) {
                require(row.isObject(), "Invalid row in " + section);
                require(seen.add(id(row.get("id"))), "Duplicate ID in " + section);
                if (!section.equals("characterRelationships")) {
                    String field = Set.of("foreshadows", "outlines", "timelineEvents").contains(section) ? "title" : "name";
                    require(row.path(field).isTextual(), "Missing " + field + " in " + section);
                }
                if (section.equals("worldviewEntries")) {
                    require(row.path("category").isTextual(), "Missing worldview category");
                }
            }
            ids.put(section, seen);
        }
        for (String section : sections) {
            for (JsonNode row : root.path(section)) {
                if (!Set.of("tags", "characterRelationships", "relationshipGroups").contains(section)) {
                    references(row, "tags", ids.get("tags"));
                }
                if (Set.of("timelineEvents", "worldviewEntries", "relationshipGroups").contains(section)) {
                    references(row, "characters", ids.get("characters"));
                }
                if (Set.of("timelineEvents", "worldviewEntries").contains(section)) {
                    references(row, "scenes", ids.get("scenes"));
                }
                if (section.equals("timelineEvents")) {
                    references(row, "foreshadows", ids.get("foreshadows"));
                    references(row, "outlines", ids.get("outlines"));
                }
                if (Set.of("scenes", "worldviewEntries").contains(section)) {
                    references(row, "mapLocations", ids.get("mapLocations"));
                }
                if (section.equals("characterRelationships")) {
                    reference(row.get("characterId1"), ids.get("characters"));
                    reference(row.get("characterId2"), ids.get("characters"));
                }
            }
        }
        parents(root.path("relationshipGroups"), "parentGroupId", false, ids.get("relationshipGroups"));
        parents(root.path("mapLocations"), "parentLocation", true, ids.get("mapLocations"));
    }

    private static void references(JsonNode row, String field, Set<Long> targets) {
        JsonNode refs = row.path(field);
        require(refs.isMissingNode() || refs.isArray(), "Invalid references: " + field);
        for (JsonNode ref : refs) reference(ref.get("id"), targets);
    }

    private static void parents(JsonNode rows, String field, boolean nested, Set<Long> targets) {
        Map<Long, Long> parents = new HashMap<>();
        for (JsonNode row : rows) {
            if (row.hasNonNull(field)) {
                JsonNode parent = nested ? row.get(field).get("id") : row.get(field);
                reference(parent, targets);
                parents.put(id(row.get("id")), id(parent));
            }
        }
        Set<Long> checked = new HashSet<>();
        for (Long start : parents.keySet()) {
            Set<Long> path = new HashSet<>();
            Long current = start;
            while (current != null && !checked.contains(current)) {
                require(path.add(current), "Cyclic hierarchy: " + field);
                current = parents.get(current);
            }
            checked.addAll(path);
        }
    }

    private static void reference(JsonNode node, Set<Long> targets) {
        require(targets.contains(id(node)), "Reference points to a missing record");
    }

    private static long id(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0,
                "Record and reference IDs must be positive integers");
        return node.asLong();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
