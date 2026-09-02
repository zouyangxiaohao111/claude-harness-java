package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LSP 结果格式化器 · 对齐 CC {@code Open-ClaudeCode/src/tools/LSPTool/formatters.ts}（全族 8 个
 * formatter + resultCount/fileCount 统计，CC LSPTool.ts formatResult :636-829）。
 *
 * <p>[IMP-D G13②] 新建：LspTool.java 原样返回 {@code raw.toString()} JSON，偏离 CC 的
 * 人类可读文本格式（formatters.ts）。本类以 CC formatters.ts 逐字语义对 JsonNode 响应做
 * 格式化，返回 {@code text + resultCount + fileCount} 三元组。
 *
 * <p>LSP 响应形状（vscode-languageserver-types）与 CC formatters.ts 入参一一对应：
 * goToDefinition/goToImplementation → Location|LocationLink[]；findReferences → Location[]；
 * hover → Hover；documentSymbol → DocumentSymbol[]|SymbolInformation[]；
 * workspaceSymbol → SymbolInformation[]；prepareCallHierarchy → CallHierarchyItem[]；
 * incomingCalls/outgoingCalls → CallHierarchyIncomingCall[]/OutgoingCall[]。
 */
final class LspResultFormatter {

    private LspResultFormatter() {
    }

    /** 格式化结果三元组 · CC formatResult 返回 { formatted, resultCount, fileCount }。 */
    record Formatted(String text, int resultCount, int fileCount) {
    }

    /**
     * 按 operation 分发 · CC LSPTool.ts:636-829 formatResult。
     *
     * @param operation LSP 操作名（9 种）
     * @param result    LSP 响应（JsonNode；null/空 → 空文本 + 0 计数）
     * @param cwd       工作目录（相对路径化基准；null → 绝对路径）
     */
    static Formatted format(String operation, JsonNode result, Path cwd) {
        switch (operation) {
            case "goToDefinition":
            case "goToImplementation": {
                List<JsonNode> locations = toLocations(result);
                List<JsonNode> valid = validLocations(locations);
                if (valid.isEmpty()) {
                    return new Formatted(
                        "No definition found. This may occur if the cursor is not on a symbol, or if the definition is in an external library not indexed by the LSP server.",
                        0, 0);
                }
                if (valid.size() == 1) {
                    return new Formatted("Defined in " + formatLocation(valid.get(0), cwd),
                        valid.size(), countUniqueFiles(valid));
                }
                List<String> lines = new ArrayList<>();
                lines.add("Found " + valid.size() + " definitions:");
                for (JsonNode loc : valid) {
                    lines.add("  " + formatLocation(loc, cwd));
                }
                return new Formatted(String.join("\n", lines), valid.size(), countUniqueFiles(valid));
            }
            case "findReferences": {
                List<JsonNode> locations = arrayItems(result);
                List<JsonNode> valid = validLocations(locations);
                if (valid.isEmpty()) {
                    return new Formatted(
                        "No references found. This may occur if the symbol has no usages, or if the LSP server has not fully indexed the workspace.",
                        0, 0);
                }
                if (valid.size() == 1) {
                    return new Formatted("Found 1 reference:\n  " + formatLocation(valid.get(0), cwd),
                        valid.size(), countUniqueFiles(valid));
                }
                Map<String, List<JsonNode>> byFile = groupByFile(valid, cwd);
                List<String> lines = new ArrayList<>();
                lines.add("Found " + valid.size() + " references across " + byFile.size() + " files:");
                for (Map.Entry<String, List<JsonNode>> e : byFile.entrySet()) {
                    lines.add("\n" + e.getKey() + ":");
                    for (JsonNode loc : e.getValue()) {
                        JsonNode start = loc.path("range").path("start");
                        lines.add("  Line " + (start.path("line").asInt(0) + 1) + ":"
                            + (start.path("character").asInt(0) + 1));
                    }
                }
                return new Formatted(String.join("\n", lines), valid.size(), countUniqueFiles(valid));
            }
            case "hover": {
                if (result == null || result.isNull() || result.isEmpty()) {
                    return new Formatted(
                        "No hover information available. This may occur if the cursor is not on a symbol, or if the LSP server has not fully indexed the file.",
                        0, 0);
                }
                String content = extractMarkupText(result.get("contents"));
                JsonNode range = result.get("range");
                if (range != null && !range.isNull()) {
                    JsonNode start = range.path("start");
                    int line = start.path("line").asInt(0) + 1;
                    int character = start.path("character").asInt(0) + 1;
                    return new Formatted("Hover info at " + line + ":" + character + ":\n\n" + content, 1, 1);
                }
                return new Formatted(content, 1, 1);
            }
            case "documentSymbol": {
                List<JsonNode> symbols = arrayItems(result);
                if (symbols.isEmpty()) {
                    return new Formatted(
                        "No symbols found in document. This may occur if the file is empty, not supported by the LSP server, or if the server has not fully indexed the file.",
                        0, 0);
                }
                JsonNode first = symbols.get(0);
                boolean isSymbolInformation = first.has("location");
                if (isSymbolInformation) {
                    // 委托 workspaceSymbol formatter（CC :353-355）
                    return formatWorkspaceSymbol(symbols, cwd);
                }
                List<String> lines = new ArrayList<>();
                lines.add("Document symbols:");
                int count = 0;
                for (JsonNode symbol : symbols) {
                    lines.addAll(formatDocumentSymbolNode(symbol, 0));
                    count += countSymbols(symbol);
                }
                return new Formatted(String.join("\n", lines), count, 1);
            }
            case "workspaceSymbol":
                return formatWorkspaceSymbol(arrayItems(result), cwd);
            case "prepareCallHierarchy": {
                List<JsonNode> items = arrayItems(result);
                if (items.isEmpty()) {
                    return new Formatted("No call hierarchy item found at this position", 0, 0);
                }
                if (items.size() == 1) {
                    return new Formatted("Call hierarchy item: " + formatCallHierarchyItem(items.get(0), cwd),
                        items.size(), countUniqueUris(items, "uri"));
                }
                List<String> lines = new ArrayList<>();
                lines.add("Found " + items.size() + " call hierarchy items:");
                for (JsonNode item : items) {
                    lines.add("  " + formatCallHierarchyItem(item, cwd));
                }
                return new Formatted(String.join("\n", lines), items.size(), countUniqueUris(items, "uri"));
            }
            case "incomingCalls": {
                List<JsonNode> calls = arrayItems(result);
                if (calls.isEmpty()) {
                    return new Formatted("No incoming calls found (nothing calls this function)", 0, 0);
                }
                Map<String, List<JsonNode>> byFile = new LinkedHashMap<>();
                for (JsonNode call : calls) {
                    JsonNode from = call.get("from");
                    if (from == null) {
                        continue;
                    }
                    String filePath = formatUri(text(from, "uri"), cwd);
                    byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(call);
                }
                List<String> lines = new ArrayList<>();
                lines.add("Found " + calls.size() + " incoming calls:");
                for (Map.Entry<String, List<JsonNode>> e : byFile.entrySet()) {
                    lines.add("\n" + e.getKey() + ":");
                    for (JsonNode call : e.getValue()) {
                        JsonNode from = call.get("from");
                        lines.add(formatCallSiteLine(from, call.get("fromRanges"), "calls at"));
                    }
                }
                return new Formatted(String.join("\n", lines), calls.size(), byFile.size());
            }
            case "outgoingCalls": {
                List<JsonNode> calls = arrayItems(result);
                if (calls.isEmpty()) {
                    return new Formatted("No outgoing calls found (this function calls nothing)", 0, 0);
                }
                Map<String, List<JsonNode>> byFile = new LinkedHashMap<>();
                for (JsonNode call : calls) {
                    JsonNode to = call.get("to");
                    if (to == null) {
                        continue;
                    }
                    String filePath = formatUri(text(to, "uri"), cwd);
                    byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(call);
                }
                List<String> lines = new ArrayList<>();
                lines.add("Found " + calls.size() + " outgoing calls:");
                for (Map.Entry<String, List<JsonNode>> e : byFile.entrySet()) {
                    lines.add("\n" + e.getKey() + ":");
                    for (JsonNode call : e.getValue()) {
                        JsonNode to = call.get("to");
                        lines.add(formatCallSiteLine(to, call.get("fromRanges"), "called from"));
                    }
                }
                return new Formatted(String.join("\n", lines), calls.size(), byFile.size());
            }
            default:
                return new Formatted(result == null ? "" : result.toString(), 0, 0);
        }
    }

    // ──────────────── workspaceSymbol / documentSymbol ────────────────

    /** CC formatWorkspaceSymbolResult（formatters.ts:371-422）。 */
    private static Formatted formatWorkspaceSymbol(List<JsonNode> symbols, Path cwd) {
        List<JsonNode> valid = new ArrayList<>();
        for (JsonNode s : symbols) {
            if (s != null && !s.isNull() && s.has("location") && s.get("location").has("uri")) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return new Formatted(
                "No symbols found in workspace. This may occur if the workspace is empty, or if the LSP server has not finished indexing the project.",
                0, 0);
        }
        Map<String, List<JsonNode>> byFile = groupSymbolsByFile(valid, cwd);
        List<String> lines = new ArrayList<>();
        lines.add("Found " + valid.size() + (valid.size() == 1 ? " symbol" : " symbols") + " in workspace:");
        for (Map.Entry<String, List<JsonNode>> e : byFile.entrySet()) {
            lines.add("\n" + e.getKey() + ":");
            for (JsonNode symbol : e.getValue()) {
                String kind = symbolKindToString(symbol.path("kind").asInt(0));
                int line = symbol.path("location").path("range").path("start").path("line").asInt(0) + 1;
                StringBuilder sb = new StringBuilder("  ").append(symbol.path("name").asText(""))
                    .append(" (").append(kind).append(") - Line ").append(line);
                String container = symbol.path("containerName").asText(null);
                if (container != null && !container.isEmpty()) {
                    sb.append(" in ").append(container);
                }
                lines.add(sb.toString());
            }
        }
        return new Formatted(String.join("\n", lines), valid.size(), countUniqueUris(valid, "location", "uri"));
    }

    /** CC formatDocumentSymbolNode（formatters.ts:307-333）：缩进 + kind + detail + Line。 */
    private static List<String> formatDocumentSymbolNode(JsonNode symbol, int indent) {
        List<String> lines = new ArrayList<>();
        String prefix = "  ".repeat(indent);
        String kind = symbolKindToString(symbol.path("kind").asInt(0));
        StringBuilder sb = new StringBuilder(prefix).append(symbol.path("name").asText(""))
            .append(" (").append(kind).append(")");
        String detail = symbol.path("detail").asText(null);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" ").append(detail);
        }
        int line = symbol.path("range").path("start").path("line").asInt(0) + 1;
        sb.append(" - Line ").append(line);
        lines.add(sb.toString());
        JsonNode children = symbol.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                lines.addAll(formatDocumentSymbolNode(child, indent + 1));
            }
        }
        return lines;
    }

    /** CC countSymbols（LSPTool.ts:518-526）：含嵌套 children 的符号总数。 */
    private static int countSymbols(JsonNode symbol) {
        int count = 1;
        JsonNode children = symbol.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                count += countSymbols(child);
            }
        }
        return count;
    }

    // ──────────────── call hierarchy ────────────────

    /** CC formatCallHierarchyItem（formatters.ts:428-449）。 */
    private static String formatCallHierarchyItem(JsonNode item, Path cwd) {
        String name = item.path("name").asText("");
        String kind = symbolKindToString(item.path("kind").asInt(0));
        String uri = text(item, "uri");
        if (uri == null) {
            return name + " (" + kind + ") - <unknown location>";
        }
        String filePath = formatUri(uri, cwd);
        int line = item.path("range").path("start").path("line").asInt(0) + 1;
        String result = name + " (" + kind + ") - " + filePath + ":" + line;
        String detail = item.path("detail").asText(null);
        if (detail != null && !detail.isEmpty()) {
            result += " [" + detail + "]";
        }
        return result;
    }

    /** CC formatIncomingCallsResult/formatOutgoingCallsResult 的调用点行（formatters.ts:515-529/577-591）。 */
    private static String formatCallSiteLine(JsonNode item, JsonNode fromRanges, String label) {
        String name = item.path("name").asText("");
        String kind = symbolKindToString(item.path("kind").asInt(0));
        int line = item.path("range").path("start").path("line").asInt(0) + 1;
        String result = "  " + name + " (" + kind + ") - Line " + line;
        if (fromRanges != null && fromRanges.isArray() && fromRanges.size() > 0) {
            List<String> sites = new ArrayList<>();
            for (JsonNode r : fromRanges) {
                JsonNode start = r.path("start");
                sites.add((start.path("line").asInt(0) + 1) + ":" + (start.path("character").asInt(0) + 1));
            }
            result += " [" + label + ": " + String.join(", ", sites) + "]";
        }
        return result;
    }

    // ──────────────── 通用辅助 ────────────────

    /** 统一化 Location 列表：Location / LocationLink / 单对象 → List<Location>（CC toLocation）。 */
    private static List<JsonNode> toLocations(JsonNode result) {
        if (result == null || result.isNull()) {
            return List.of();
        }
        if (result.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            result.forEach(out::add);
            return out;
        }
        return List.of(result);
    }

    /** 过滤无 uri 的 location（CC formatResult validLocations；LocationLink 取 targetUri）。 */
    private static List<JsonNode> validLocations(List<JsonNode> locations) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode loc : locations) {
            if (loc != null && !loc.isNull() && locationUri(loc) != null) {
                out.add(loc);
            }
        }
        return out;
    }

    /** LocationLink(targetUri) 与 Location(uri) 的 uri 提取（CC isLocationLink/toLocation 等价）。 */
    private static String locationUri(JsonNode loc) {
        if (loc == null || loc.isNull()) {
            return null;
        }
        JsonNode target = loc.get("targetUri");
        if (target != null && target.isTextual() && !target.asText().isEmpty()) {
            return target.asText();
        }
        JsonNode uri = loc.get("uri");
        return uri != null && uri.isTextual() && !uri.asText().isEmpty() ? uri.asText() : null;
    }

    /** 数组化（CC `(result as X[]) || []`）。 */
    private static List<JsonNode> arrayItems(JsonNode result) {
        if (result == null || !result.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        result.forEach(out::add);
        return out;
    }

    /** CC countUniqueFiles（LSPTool.ts:531-533）：unique uri 数（LocationLink 取 targetUri）。 */
    private static int countUniqueFiles(List<JsonNode> locations) {
        java.util.Set<String> uris = new java.util.HashSet<>();
        for (JsonNode loc : locations) {
            String uri = locationUri(loc);
            if (uri != null) {
                uris.add(uri);
            }
        }
        return uris.size();
    }

    /** 从符号列表（location.uri）统计 unique 文件。 */
    private static int countUniqueUris(List<JsonNode> items, String... pathKeys) {
        java.util.Set<String> uris = new java.util.HashSet<>();
        for (JsonNode item : items) {
            JsonNode node = item;
            for (String key : pathKeys) {
                node = node == null ? null : node.get(key);
            }
            if (node != null && node.isTextual() && !node.asText().isEmpty()) {
                uris.add(node.asText());
            }
        }
        return uris.size();
    }

    /** CC groupByFile（formatters.ts:78-94）：按 formatUri 后路径分组（LocationLink 取 targetUri）。 */
    private static Map<String, List<JsonNode>> groupByFile(List<JsonNode> items, Path cwd) {
        Map<String, List<JsonNode>> byFile = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String uri = locationUri(item);
            String filePath = formatUri(uri == null ? "" : uri, cwd);
            byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(item);
        }
        return byFile;
    }

    /** CC groupByFile 的 SymbolInformation 变体（location.uri）。 */
    private static Map<String, List<JsonNode>> groupSymbolsByFile(List<JsonNode> symbols, Path cwd) {
        Map<String, List<JsonNode>> byFile = new LinkedHashMap<>();
        for (JsonNode s : symbols) {
            String uri = s.path("location").path("uri").asText("");
            String filePath = formatUri(uri, cwd);
            byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(s);
        }
        return byFile;
    }

    /** CC formatLocation（formatters.ts:99-104）：filePath:line:character（1-based；LocationLink 取 targetUri）。 */
    private static String formatLocation(JsonNode location, Path cwd) {
        String uri = locationUri(location);
        String filePath = formatUri(uri == null ? "" : uri, cwd);
        JsonNode start = location.path("range").path("start");
        int line = start.path("line").asInt(0) + 1;
        int character = start.path("character").asInt(0) + 1;
        return filePath + ":" + line + ":" + character;
    }

    /**
     * CC formatUri（formatters.ts:24-72）：去 file:// 协议 → Windows 盘符去前导 '/' →
     * decodeURIComponent 解码 → cwd 相对路径（更短且不以 ../../ 开头时）→ 正斜杠归一。
     */
    private static String formatUri(String uri, Path cwd) {
        if (uri == null || uri.isEmpty()) {
            return "<unknown location>";
        }
        String filePath = uri.startsWith("file://") ? uri.substring("file://".length()) : uri;
        // Windows: file:///C:/path → /C:/path → C:/path
        if (filePath.length() >= 2 && filePath.charAt(0) == '/' && isDriveLetter(filePath.charAt(1))) {
            filePath = filePath.substring(1);
        }
        filePath = percentDecode(filePath);
        if (cwd != null) {
            try {
                Path abs = java.nio.file.Paths.get(filePath);
                String relative = cwd.toAbsolutePath().normalize()
                    .relativize(abs.toAbsolutePath().normalize()).toString().replace('\\', '/');
                if (relative.length() < filePath.length() && !relative.startsWith("../../")) {
                    return relative;
                }
            } catch (Exception e) {
                // malformed path → 保持解码后的绝对路径（CC 注释：use un-decoded path if malformed）
            }
        }
        return filePath.replace('\\', '/');
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** decodeURIComponent 等价（不抛异常；非法编码保留原文）。 */
    private static String percentDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** CC extractMarkupText（formatters.ts:223-248）：MarkupContent | MarkedString | MarkedString[]。 */
    private static String extractMarkupText(JsonNode contents) {
        if (contents == null || contents.isNull()) {
            return "";
        }
        if (contents.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : contents) {
                if (item.isTextual()) {
                    parts.add(item.asText());
                } else {
                    parts.add(item.path("value").asText(""));
                }
            }
            return String.join("\n\n", parts);
        }
        if (contents.isTextual()) {
            return contents.asText();
        }
        return contents.path("value").asText("");
    }

    /** CC symbolKindToString（formatters.ts:272-302）：SymbolKind 枚举 → 可读字符串。 */
    private static String symbolKindToString(int kind) {
        return switch (kind) {
            case 1 -> "File";
            case 2 -> "Module";
            case 3 -> "Namespace";
            case 4 -> "Package";
            case 5 -> "Class";
            case 6 -> "Method";
            case 7 -> "Property";
            case 8 -> "Field";
            case 9 -> "Constructor";
            case 10 -> "Enum";
            case 11 -> "Interface";
            case 12 -> "Function";
            case 13 -> "Variable";
            case 14 -> "Constant";
            case 15 -> "String";
            case 16 -> "Number";
            case 17 -> "Boolean";
            case 18 -> "Array";
            case 19 -> "Object";
            case 20 -> "Key";
            case 21 -> "Null";
            case 22 -> "EnumMember";
            case 23 -> "Struct";
            case 24 -> "Event";
            case 25 -> "Operator";
            case 26 -> "TypeParameter";
            default -> "Unknown";
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode n = node.get(field);
        return n != null && n.isTextual() && !n.asText().isEmpty() ? n.asText() : null;
    }
}
