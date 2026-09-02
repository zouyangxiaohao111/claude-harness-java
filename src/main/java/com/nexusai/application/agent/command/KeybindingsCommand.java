package com.nexusai.application.agent.command;

import java.util.function.Function;

/**
 * Keybindings 模板生成 + 编辑器打开 · 对齐 CC commands/keybindings/keybindings.ts call.
 *
 * <p>L1 语义: 检查 keybinding customization 是否启用; 不启用 → 返回 preview 消息; 启用则
 *            创建 template 文件 (wx exclusive flag 避免 TOCTOU) + 打开到编辑器.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(Environment) → CommandResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: !isEnabled → "preview" 消息; 启用且 wx 成功 → Created; wx EEXIST → Opened</li>
 *   <li><b>A3</b>: mkdir 失败抛 → 异常透传; wx EEXIST → fileExists=true 继续打开</li>
 *   <li><b>A4</b>: editor 打开失败 → 消息含 "Could not open in editor: {error}"</li>
 *   <li><b>A5</b>: 真实场景 — 已存在 keybindings.json → "Opened /path/keybindings.json in your editor."</li>
 * </ul>
 *
 * <p>L3 (Java idiom): FS 副作用 (mkdir/writeFile/editFileInEditor) 通过 Function&lt;String, Boolean&gt; writer
 *                    + Function&lt;String, EditorResult&gt; editor 注入测试可控; CC EEXIST errno 码检查
 *                    → Java IOException 包装 + ErrnoCode 注入.
 */
public class KeybindingsCommand {

    /** editor 调用结果 (CC editFileInEditor 返回). */
    public record EditorResult(String error) {
        public static EditorResult ok() { return new EditorResult(null); }
        public static EditorResult error(String err) { return new EditorResult(err); }
        public boolean hasError() { return error != null; }
    }

    /** execute 注入的 FS / editor 依赖. */
    public record Environment(
        boolean isCustomizationEnabled,
        String keybindingsPath,
        Function<String, Boolean> writer,        // writeFile(path) → true if EEXIST (file existed), false if newly written
        Function<String, EditorResult> editor   // editFileInEditor(path) → EditorResult
    ) {}

    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) { return new CommandResult("text", value); }
    }

    private static final String PREVIEW_MESSAGE =
        "Keybinding customization is not enabled. This feature is currently in preview.";

    /**
     * 执行 keybindings 命令 (CC call).
     */
    public CommandResult execute(Environment env) {
        if (!env.isCustomizationEnabled()) {
            return CommandResult.text(PREVIEW_MESSAGE);
        }
        String path = env.keybindingsPath;
        boolean fileExists = env.writer.apply(path);
        EditorResult editorResult = env.editor.apply(path);
        if (editorResult.hasError()) {
            String verb = fileExists ? "Opened" : "Created";
            return CommandResult.text(verb + " " + path + ". Could not open in editor: " + editorResult.error());
        }
        return CommandResult.text(fileExists
            ? "Opened " + path + " in your editor."
            : "Created " + path + " with template. Opened in your editor.");
    }
}