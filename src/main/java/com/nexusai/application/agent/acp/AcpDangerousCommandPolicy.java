package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ACP 危险命令硬阻断 · 照 Octop {@code acp/permissions.py:11-18} 的清单。
 *
 * <p>本仓的 {@code DangerousPatternDetector} 判的是<b>权限规则</b>、不看命令文本；
 * {@code renderPermissionWarning} 只对 {@code Bash}/{@code PowerShell} 生效且受 feature
 * flag 门控 —— 外部 agent 的 \"execute\" 请求不会被既有任何层自动拦。这里补一道，
 * <b>先于任何放行策略</b>（allow/yolo 也不能绕过）：
 * <ol>
 *   <li>命令文本命中 {@code rm -rf /} / {@code sudo rm -rf} / {@code mkfs} / {@code dd if=}</li>
 *   <li>工具声明的路径落在工作目录之外（越界）</li>
 * </ol>
 */
public final class AcpDangerousCommandPolicy {

    /** 硬阻断命令片段（小写包含匹配）。 */
    static final List<String> BLOCKED_COMMAND_PATTERNS = List.of(
            "rm -rf /",
            "sudo rm -rf",
            "mkfs",
            "dd if="
    );

    /**
     * 判定结果。
     *
     * @param blocked 是否硬阻断
     * @param reason  阻断原因（只写日志，不进给模型的正文）
     */
    public record Decision(boolean blocked, String reason) {
        static Decision allow() {
            return new Decision(false, null);
        }

        static Decision block(String reason) {
            return new Decision(true, reason);
        }
    }

    private AcpDangerousCommandPolicy() {
    }

    /** 判定一次工具调用是否必须硬阻断。 */
    public static Decision check(JsonNode toolCall, Path cwd) {
        if (toolCall == null || !toolCall.isObject()) {
            return Decision.allow();
        }
        String command = extractCommand(toolCall);
        if (command != null) {
            String lower = command.toLowerCase();
            for (String pattern : BLOCKED_COMMAND_PATTERNS) {
                if (lower.contains(pattern)) {
                    return Decision.block("命中危险命令清单: " + pattern);
                }
            }
        }
        Path base = basePath(cwd);
        for (String raw : extractPaths(toolCall)) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(raw);
                if (!candidate.isAbsolute()) {
                    candidate = base.resolve(candidate);
                }
                candidate = candidate.normalize();
                if (!startsWithIgnoreCase(candidate, base)) {
                    return Decision.block("路径越界（不在工作目录 " + base + " 内）: " + raw);
                }
            } catch (RuntimeException e) {
                return Decision.block("路径无法解析: " + raw);
            }
        }
        return Decision.allow();
    }

    /** 提取命令文本：{@code rawInput.command}，或 {@code rawInput.args/argv} 拼接。 */
    static String extractCommand(JsonNode toolCall) {
        JsonNode rawInput = toolCall.get("rawInput");
        if (rawInput == null || !rawInput.isObject()) {
            return null;
        }
        String command = rawInput.path("command").asText("");
        if (!command.isBlank()) {
            return command;
        }
        JsonNode argv = rawInput.has("args") ? rawInput.get("args") : rawInput.get("argv");
        if (argv != null && argv.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : argv) {
                String text = item.isTextual() ? item.asText() : item.toString();
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
            if (!parts.isEmpty()) {
                return String.join(" ", parts);
            }
        }
        return null;
    }

    /** 提取工具要触碰的路径（locations / rawInput.path / paths / diff content，去重保序）。 */
    static List<String> extractPaths(JsonNode toolCall) {
        Set<String> paths = new LinkedHashSet<>();
        JsonNode locations = toolCall.get("locations");
        if (locations != null && locations.isArray()) {
            for (JsonNode location : locations) {
                addPath(paths, location.path("path").asText(null));
            }
        }
        JsonNode content = toolCall.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if (block.isObject() && "diff".equals(block.path("type").asText(""))) {
                    addPath(paths, block.path("path").asText(null));
                }
            }
        }
        JsonNode rawInput = toolCall.get("rawInput");
        if (rawInput != null && rawInput.isObject()) {
            addPath(paths, rawInput.path("path").asText(null));
            addPath(paths, rawInput.path("file_path").asText(null));
            JsonNode list = rawInput.get("paths");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    addPath(paths, item.isTextual() ? item.asText() : null);
                }
            }
        }
        return new ArrayList<>(paths);
    }

    private static void addPath(Set<String> sink, String value) {
        if (value != null && !value.isBlank()) {
            sink.add(value.trim());
        }
    }

    private static Path basePath(Path cwd) {
        Path base = cwd != null ? cwd : Path.of(System.getProperty("user.dir", "."));
        return base.toAbsolutePath().normalize();
    }

    private static boolean startsWithIgnoreCase(Path candidate, Path base) {
        if (base.getNameCount() == 0) {
            return true;
        }
        if (candidate.getNameCount() < base.getNameCount()) {
            return false;
        }
        for (int i = 0; i < base.getNameCount(); i++) {
            String a = base.getName(i).toString();
            String b = candidate.getName(i).toString();
            if (!a.equalsIgnoreCase(b)) {
                return false;
            }
        }
        return true;
    }
}
