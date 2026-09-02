package com.nexusai.application.agent.telemetry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * 文件扩展名提取器 · 对齐 CC Open-ClaudeCode/src/services/analytics/metadata.ts:311-360.
 *
 * <p><b>[R32-b12 D-9 P1 必修]</b> CC 真源：
 * <ul>
 *   <li>{@code getFileExtensionForAnalytics(filePath)} —— 单文件路径扩展名解析
 *       (metadata.ts:311-337), 长扩展名（> 10）→ "other" 防 hash-based filename 防护</li>
 *   <li>{@code getFileExtensionsFromBashCommand(command, simulatedFilePath)} ——
 *       bash command 解析文件参数 (metadata.ts:340-360), 16 个白名单命令</li>
 * </ul>
 *
 * <h2>用途</h2>
 * <p>OTel {@code tengu_tool_use_success} 事件的 {@code fileExtension} 字段.
 * 用于分析工具使用模式（哪类文件最常被 Read/Write/Edit）.
 *
 * @since R32-b12
 */
public final class FileExtensionExtractor {

    /**
     * 扩展名长度上限. 严格对齐 CC MAX_FILE_EXTENSION_LENGTH = 10.
     * 长扩展名 → "other"（hash-based filename 防护）.
     */
    public static final int MAX_EXTENSION_LENGTH = 10;

    /**
     * bash 文件命令白名单. 严格对齐 CC FILE_COMMANDS Set (metadata.ts:340-350).
     * 仅这些命令的参数会被解析为文件路径，其他命令（curl / wget / npm 等）跳过.
     */
    public static final Set<String> FILE_COMMANDS = Set.of(
        "rm", "mv", "cp", "touch", "mkdir", "chmod", "chown",
        "cat", "head", "tail", "sort", "stat", "diff", "wc",
        "grep", "rg", "sed", "awk"
    );

    private FileExtensionExtractor() {}

    /**
     * 提取文件路径的扩展名 · 对齐 CC getFileExtensionForAnalytics.
     *
     * <p>规则：
     * <ol>
     *   <li>用 {@link Path#getFileName()} + 字符串解析取扩展名（Java NIO 不直接提供 extname）</li>
     *   <li>无扩展名 / 仅点号 → null</li>
     *   <li>扩展名长度 > {@link #MAX_EXTENSION_LENGTH} → "other"（避免 hash-based filename 污染分析）</li>
     *   <li>否则返回去掉前导点的扩展名（小写）</li>
     * </ol>
     *
     * @param filePath 文件路径（绝对 / 相对均可；可为 null）
     * @return 扩展名字符串（小写，无点）；{@code "other"} 表示过长；{@code null} 表示无扩展名或输入无效
     */
    public static String getFileExtensionForAnalytics(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(filePath);
            String filename = path.getFileName() != null
                ? path.getFileName().toString()
                : filePath;
            int dotIdx = filename.lastIndexOf('.');
            if (dotIdx <= 0 || dotIdx == filename.length() - 1) {
                // 无扩展名 / "file." / ".hidden" (以点开头的隐藏文件 CC: ext=='.')
                return null;
            }
            String ext = filename.substring(dotIdx + 1).toLowerCase(java.util.Locale.ROOT);
            if (ext.isEmpty()) {
                return null;
            }
            if (ext.length() > MAX_EXTENSION_LENGTH) {
                return "other";
            }
            return ext;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 bash command 中提取文件扩展名 · 对齐 CC getFileExtensionsFromBashCommand.
     *
     * <p>规则：
     * <ol>
     *   <li>优先使用 {@code simulatedFilePath}（CC: 来自 _simulatedSedEdit.filePath）</li>
     *   <li>否则解析 command: 取第一个 token (命令名), 若在 {@link #FILE_COMMANDS} 白名单内,
     *       提取下一个非 flag 参数的文件路径扩展名</li>
     *   <li>否则返回 null（不解析非白名单命令的参数）</li>
     * </ol>
     *
     * <p>简化版：仅取 command 中第一个文件参数；不解析复杂 bash 语法 (pipe / redirect / glob).
     * 与 CC 真源 metadata.ts:340+ 完整实现的简化差异已在 b12 explore findings §2.13 标注.
     *
     * @param command bash 命令字符串（可为 null）
     * @param simulatedFilePath 模拟文件路径（如 _simulatedSedEdit.filePath），优先使用
     * @return 扩展名；{@code "other"} 表示过长；{@code null} 表示无扩展名 / 非白名单命令 / 输入无效
     */
    public static String getFileExtensionsFromBashCommand(String command, String simulatedFilePath) {
        if (simulatedFilePath != null && !simulatedFilePath.isBlank()) {
            return getFileExtensionForAnalytics(simulatedFilePath);
        }
        if (command == null || command.isBlank()) {
            return null;
        }
        // 简单 token 解析: split by whitespace
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length < 2) {
            return null;
        }
        String cmd = tokens[0];
        // 去除路径前缀 (e.g., /usr/bin/cp → cp)
        int slashIdx = cmd.lastIndexOf('/');
        if (slashIdx >= 0) {
            cmd = cmd.substring(slashIdx + 1);
        }
        if (!FILE_COMMANDS.contains(cmd)) {
            return null;
        }
        // 找第一个非 flag 参数 (不以 "-" 开头)
        for (int i = 1; i < tokens.length; i++) {
            String arg = tokens[i];
            if (arg == null || arg.startsWith("-")) {
                continue;
            }
            return getFileExtensionForAnalytics(arg);
        }
        return null;
    }

    /**
     * 重载（无 simulatedFilePath）· 对齐 CC 默认参数.
     */
    public static String getFileExtensionsFromBashCommand(String command) {
        return getFileExtensionsFromBashCommand(command, null);
    }
}