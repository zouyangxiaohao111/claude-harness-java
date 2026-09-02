package com.nexusai.application.agent.command;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /release-notes 命令（local 型）· 对齐 CC commands/release-notes/release-notes.ts:19-50 call()。
 *
 * <p>L1 语义: 展示发布说明 — CC 先以 500ms 超时拉取 GitHub changelog（fetchAndStoreChangelog），
 * 失败回落缓存（getStoredChangelog），再无可回落链接（release-notes.ts:19-50）。Java web 后端
 * 不主动外网拉取（隐私红线：web 端不发起用户会话无关网络请求），改为读取<b>本地项目
 * CHANGELOG.md</b>（仓库内发布说明真源）——同一「有则展示、无则给链接」的回落链语义。
 *
 * <p><b>Java 通道</b>（本命令类型：CC index.ts type='local'，无 isEnabled gate）：
 * <ul>
 *   <li>index.ts:3-9 — type='local'、description 'View release notes'、supportsNonInteractive=true
 *       （web 恒交互，不建模）。</li>
 *   <li>release-notes.ts:9-17 formatReleaseNotes — {@code Version X:} 头 + {@code · note} 要点。</li>
 *   <li>release-notes.ts:19-50 call() — fresh → cached → link 三阶回落。</li>
 *   <li>utils/releaseNotes.ts:156-196 parseChangelog + :249-276 getAllReleaseNotes（oldest first）。</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #call(String)} 签名 → {@link CommandResult}</li>
 *   <li><b>A2 Golden Trace</b>: 读 CHANGELOG.md → parse → format → 返回 "Version X:\n· note..."；
 *       文件缺失/空 → 返回 changelog 链接</li>
 *   <li><b>A3</b>: 纯函数 + 文件 IO（无副作用）；解析失败容错返回空列表</li>
 *   <li><b>A4</b>: 文件读失败 → 空 → 回落链接；版本列带注释（如 "0.5.0（追加·未递增）"）保留展示、
 *       排序用数字段</li>
 *   <li><b>A5</b>: 真实场景 — 用户 /release-notes → 展示本地 CHANGELOG.md 全部版本要点</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async fetch/read → Java 同步文件读（异步由 caller wired）；
 * CC `## version` 标题格式 + 本项目 markdown 表（| Date | Version | Change |）双格式解析；
 * TS semver gt() → Java 数字段比较。
 */
public final class ReleaseNotesCommand {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNotesCommand.class);

    /** CC original: CHANGELOG_URL（utils/releaseNotes.ts:28-29）。 */
    public static final String CHANGELOG_URL =
        "https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md";

    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) {
            return new CommandResult("text", value);
        }
    }

    /**
     * CC original: call()（release-notes.ts:19-50）· 读本地 CHANGELOG.md → 解析 → 格式化。
     *
     * @param changelogPath CHANGELOG.md 绝对路径（null/空/缺失 → 回落链接）
     * @return text CommandResult（"Version X:\n· note..." 或 changelog 链接）
     */
    public CommandResult call(String changelogPath) {
        String content = readChangelog(changelogPath);
        List<Map.Entry<String, List<String>>> notes = getAllReleaseNotes(content);
        if (!notes.isEmpty()) {
            return CommandResult.text(formatReleaseNotes(notes));
        }
        // CC release-notes.ts:45-49 无可展示 → "See the full changelog at: {CHANGELOG_URL}"
        return CommandResult.text("See the full changelog at: " + CHANGELOG_URL);
    }

    /** 读本地 CHANGELOG.md（UTF-8）；失败/缺失 → 空串（CC getStoredChangelog 空缓存等价）。 */
    private static String readChangelog(String changelogPath) {
        if (changelogPath == null || changelogPath.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(changelogPath);
            if (!Files.exists(p)) {
                return "";
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[ReleaseNotesCommand] 读取 CHANGELOG 失败: path={} err={}", changelogPath, e.getMessage());
            return "";
        }
    }

    /**
     * CC original: parseChangelog（utils/releaseNotes.ts:156-196）· 兼容两种格式：
     * <ol>
     *   <li><b>本项目 markdown 表</b>：{@code | Date | Version | Change |} 行 → version → change 要点
     *       （表序即文件序，最新在前）。</li>
     *   <li><b>CC `## version` 标题</b>：按 {@code ^## } 分段，首行 version（去掉 {@code - date} 后缀），
     *       {@code - } 要点。</li>
     * </ol>
     * 首条非空要点才登记版本（对齐 CC :186-188 notes.length &gt; 0 才登记）。
     */
    public Map<String, List<String>> parseChangelog(String content) {
        Map<String, List<String>> notes = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return notes;
        }
        boolean tableHit = parseTableFormat(content, notes);
        if (!tableHit) {
            parseHeadingFormat(content, notes);
        }
        return notes;
    }

    /** markdown 表格式解析 · {@code | Date | Version | Change |}；命中数据行返回 true。 */
    private static boolean parseTableFormat(String content, Map<String, List<String>> notes) {
        boolean any = false;
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            String[] cols = line.split("\\|");
            if (cols.length < 4) {
                continue;
            }
            String dateCol = cols[1].trim();
            String versionCol = cols[2].trim();
            String changeCol = cols[3].trim();
            // 跳过表头（Date/Version/Change）与分隔行（---）
            if (dateCol.isEmpty() || versionCol.isEmpty() || changeCol.isEmpty()
                || dateCol.equalsIgnoreCase("Date")
                || dateCol.matches("-+")) {
                continue;
            }
            notes.computeIfAbsent(versionCol, k -> new ArrayList<>()).add(changeCol);
            any = true;
        }
        return any;
    }

    /** CC `## version` 标题格式解析（utils/releaseNotes.ts:164-189）。 */
    private static void parseHeadingFormat(String content, Map<String, List<String>> notes) {
        String[] sections = content.split("(?m)^## ");
        for (int i = 1; i < sections.length; i++) {
            String[] lines = sections[i].trim().split("\n");
            if (lines.length == 0) {
                continue;
            }
            // CC :176 version = 首行 ` - ` 前段（兼容 "1.2.3" 与 "1.2.3 - YYYY-MM-DD"）
            String version = lines[0].split(" - ")[0].trim();
            if (version.isEmpty()) {
                continue;
            }
            List<String> list = new ArrayList<>();
            for (int j = 1; j < lines.length; j++) {
                String l = lines[j].trim();
                if (l.startsWith("- ")) {
                    String note = l.substring(2).trim();
                    if (!note.isEmpty()) {
                        list.add(note);
                    }
                }
            }
            if (!list.isEmpty()) {
                notes.put(version, list);
            }
        }
    }

    /**
     * CC original: getAllReleaseNotes（utils/releaseNotes.ts:249-276）· 按版本<b>旧在前</b>排序
     * （CC {@code sort((a, b) => gt(a, b) ? 1 : -1)}）。本项目表序最新在前 → 反转排序得到 old→new。
     */
    public List<Map.Entry<String, List<String>>> getAllReleaseNotes(String content) {
        List<Map.Entry<String, List<String>>> result =
            new ArrayList<>(parseChangelog(content).entrySet());
        result.sort((a, b) -> compareVersion(a.getKey(), b.getKey()));
        return result;
    }

    /**
     * CC original: formatReleaseNotes（release-notes.ts:9-17）·
     * {@code Version X:\n· note\n· note} 分段，段间空行。
     */
    private static String formatReleaseNotes(List<Map.Entry<String, List<String>>> notes) {
        List<String> sections = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : notes) {
            StringBuilder sb = new StringBuilder("Version ").append(entry.getKey()).append(":");
            for (String note : entry.getValue()) {
                if (note == null || note.isBlank()) {
                    continue;
                }
                sb.append("\n· ").append(note);
            }
            sections.add(sb.toString());
        }
        return String.join("\n\n", sections);
    }

    /** 版本比较（升序 old→new）· 取首个 X.Y.Z 数字段比较（CC semver coerce+gt 的 Java 投影）。 */
    private static int compareVersion(String v1, String v2) {
        int[] p1 = versionParts(v1);
        int[] p2 = versionParts(v2);
        for (int i = 0; i < 3; i++) {
            if (p1[i] != p2[i]) {
                return Integer.compare(p1[i], p2[i]);
            }
        }
        return 0;
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** 提取版本字符串中首个 "X.Y.Z" 数字段；未命中 → {0,0,0}（CC coerce 失败兜底）。 */
    private static int[] versionParts(String v) {
        int[] parts = new int[]{0, 0, 0};
        if (v == null) {
            return parts;
        }
        Matcher m = VERSION_PATTERN.matcher(v);
        if (m.find()) {
            parts[0] = Integer.parseInt(m.group(1));
            parts[1] = Integer.parseInt(m.group(2));
            parts[2] = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        }
        return parts;
    }
}
