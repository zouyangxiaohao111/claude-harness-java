package com.nexusai.application.agent.command;

import java.util.Collection;
import java.util.function.BiFunction;

/**
 * Files-in-context 列表命令 · 对齐 CC commands/files/files.ts:7-19 call.
 *
 * <p>L1 语义: 从 readFileState cache keys 提取文件列表 → 转为相对 cwd 路径 → 拼成换行分隔文本.
 *            0 个文件 → "No files in context".
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(cwd, files, relativize) → CommandResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: 0 文件 → "No files in context"; 多文件 → "Files in context:\\nfile1\\nfile2"</li>
 *   <li><b>A3</b>: 纯函数, 无副作用</li>
 *   <li><b>A4</b>: cwd="" 或 null → relative path 仍正确; 空文件名跳过</li>
 *   <li><b>A5</b>: 真实场景 — files=["/proj/src/a.ts","/proj/b.md"], cwd="/proj" → "Files in context:\\nsrc/a.ts\\nb.md"</li>
 * </ul>
 *
 * <p>L3 (Java idiom): BiFunction 注入 relative 路径计算 (CC path.relative(from, to)); 纯字符串拼接.
 */
public final class FilesCommand {

    private FilesCommand() {}

    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) { return new CommandResult("text", value); }
    }

    /**
     * 执行 files 命令.
     *
     * @param cwd       当前工作目录 (用于相对路径转换)
     * @param files     绝对路径文件列表 (CC context.readFileState cache keys)
     * @param relativize (absolutePath, cwd) → relative path 函数 (CC path.relative)
     * @return "No files in context" 或 "Files in context:\\n..." 文本
     */
    public static CommandResult execute(String cwd, Collection<String> files,
                                        BiFunction<String, String, String> relativize) {
        if (files == null || files.isEmpty()) {
            return CommandResult.text("No files in context");
        }
        String prefix = cwd == null ? "" : cwd;
        String fileList = files.stream()
            .filter(f -> f != null && !f.isEmpty())
            .map(f -> relativize.apply(f, cwd).replaceFirst("^" + java.util.regex.Pattern.quote(prefix) + "/?", ""))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
        return CommandResult.text("Files in context:\n" + fileList);
    }
}