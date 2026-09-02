package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /keybindings-help skill · 对齐 CC skills/bundled/keybindings.ts（完整 11 段 prompt）.
 *
 * <p>CC 真源：{@code Open-ClaudeCode/src/skills/bundled/keybindings.ts} 的 {@code registerKeybindingsSkill()}
 * （:292-327）从 {@code defaultBindings.ts} / {@code schema.ts} / {@code reservedShortcuts.ts} 三个
 * 源真数组动态建表，产出 11 段 prompt：
 * <ol>
 *   <li>SECTION_INTRO（:149-160）</li>
 *   <li>SECTION_FILE_FORMAT（:162-170）</li>
 *   <li>SECTION_KEYSTROKE_SYNTAX（:172-186）</li>
 *   <li>SECTION_UNBINDING（:188-196）</li>
 *   <li>SECTION_INTERACTION（:198-204）</li>
 *   <li>SECTION_COMMON_PATTERNS（:206-219，含 Rebind a key / Add a chord binding）</li>
 *   <li>SECTION_BEHAVIORAL_RULES（:221-229）</li>
 *   <li>SECTION_DOCTOR（:231-290，含 Common Issues and Fixes / Example /doctor Output）</li>
 *   <li>## Reserved Shortcuts（generateReservedShortcuts :89-112）</li>
 *   <li>## Available Contexts（generateContextsTable :20-28）</li>
 *   <li>## Available Actions（generateActionsTable :33-56）</li>
 * </ol>
 *
 * <p>改造前偏移（探查 BD-19 高危）：仅硬编码 2 上下文 + 2 动作，formatPrompt 只输出
 * 「# Keybindings / ## Contexts / ## Actions」3 段，缺失 reserved-shortcuts/file-format/
 * keystroke-syntax/unbinding/interaction/common-patterns/behavioral-rules/doctor 等 8 段；
 * {@code isEnabled=null}（未接线 CC isKeybindingCustomizationEnabled）。本类全量补齐。
 *
 * <p><b>源真数组内嵌</b>：CC 将 KEYBINDING_CONTEXTS/KEYBINDING_ACTIONS/DEFAULT_BINDINGS/
 * NON_REBINDABLE/TERMINAL_RESERVED/MACOS_RESERVED 作为模块级常量导入。Java 等价为静态常量
 * （本类内嵌），generate* 纯函数从常量动态建表，不依赖注入 supplier。
 */
public final class KeybindingsSkill {

    private static final Logger log = LoggerFactory.getLogger(KeybindingsSkill.class);

    /** CC original: keybindings.ts:294 {@code name: 'keybindings-help'}. */
    public static final String SKILL_NAME = "keybindings-help";

    /** CC original: keybindings.ts:296 description（含 Examples 尾句）. */
    public static final String SKILL_DESCRIPTION =
        "Use when the user wants to customize keyboard shortcuts, rebind keys, add chord bindings, "
        + "or modify ~/.nexusai/keybindings.json. Examples: \"rebind ctrl+s\", \"add a chord shortcut\", "
        + "\"change the submit key\", \"customize keybindings\".";

    // ════════════════════════════════════════════════════════════════════════
    // 源真数组（CC keybindings/schema.ts + defaultBindings.ts + reservedShortcuts.ts）
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: KEYBINDING_CONTEXTS + KEYBINDING_CONTEXT_DESCRIPTIONS (schema.ts:12-59)，18 项，插入序即表格行序. */
    private static final Map<String, String> CONTEXTS = new LinkedHashMap<>();
    static {
        CONTEXTS.put("Global", "Active everywhere, regardless of focus");
        CONTEXTS.put("Chat", "When the chat input is focused");
        CONTEXTS.put("Autocomplete", "When autocomplete menu is visible");
        CONTEXTS.put("Confirmation", "When a confirmation/permission dialog is shown");
        CONTEXTS.put("Help", "When the help overlay is open");
        CONTEXTS.put("Transcript", "When viewing the transcript");
        CONTEXTS.put("HistorySearch", "When searching command history (ctrl+r)");
        CONTEXTS.put("Task", "When a task/agent is running in the foreground");
        CONTEXTS.put("ThemePicker", "When the theme picker is open");
        CONTEXTS.put("Settings", "When the settings menu is open");
        CONTEXTS.put("Tabs", "When tab navigation is active");
        CONTEXTS.put("Attachments", "When navigating image attachments in a select dialog");
        CONTEXTS.put("Footer", "When footer indicators are focused");
        CONTEXTS.put("MessageSelector", "When the message selector (rewind) is open");
        CONTEXTS.put("DiffDialog", "When the diff dialog is open");
        CONTEXTS.put("ModelPicker", "When the model picker is open");
        CONTEXTS.put("Select", "When a select/list component is focused");
        CONTEXTS.put("Plugin", "When the plugin dialog is open");
    }

    /** CC original: KEYBINDING_ACTIONS (schema.ts:64-172)，86 项，插入序即表格行序. */
    private static final List<String> ACTIONS = List.of(
        "app:interrupt", "app:exit", "app:toggleTodos", "app:toggleTranscript", "app:toggleBrief",
        "app:toggleTeammatePreview", "app:toggleTerminal", "app:redraw", "app:globalSearch", "app:quickOpen",
        "history:search", "history:previous", "history:next",
        "chat:cancel", "chat:killAgents", "chat:cycleMode", "chat:modelPicker", "chat:fastMode",
        "chat:thinkingToggle", "chat:submit", "chat:newline", "chat:undo", "chat:externalEditor",
        "chat:stash", "chat:imagePaste", "chat:messageActions",
        "autocomplete:accept", "autocomplete:dismiss", "autocomplete:previous", "autocomplete:next",
        "confirm:yes", "confirm:no", "confirm:previous", "confirm:next", "confirm:nextField",
        "confirm:previousField", "confirm:cycleMode", "confirm:toggle", "confirm:toggleExplanation",
        "tabs:next", "tabs:previous",
        "transcript:toggleShowAll", "transcript:exit",
        "historySearch:next", "historySearch:accept", "historySearch:cancel", "historySearch:execute",
        "task:background",
        "theme:toggleSyntaxHighlighting",
        "help:dismiss",
        "attachments:next", "attachments:previous", "attachments:remove", "attachments:exit",
        "footer:up", "footer:down", "footer:next", "footer:previous", "footer:openSelected",
        "footer:clearSelection", "footer:close",
        "messageSelector:up", "messageSelector:down", "messageSelector:top", "messageSelector:bottom",
        "messageSelector:select",
        "diff:dismiss", "diff:previousSource", "diff:nextSource", "diff:back", "diff:viewDetails",
        "diff:previousFile", "diff:nextFile",
        "modelPicker:decreaseEffort", "modelPicker:increaseEffort",
        "select:next", "select:previous", "select:accept", "select:cancel",
        "plugin:toggle", "plugin:install",
        "permission:toggleDebug",
        "settings:search", "settings:retry", "settings:close",
        "voice:pushToTalk"
    );

    /**
     * 单条默认键位 · CC defaultBindings.ts {@code block.bindings} 的 key → action 条目.
     *
     * @param context CC block.context（如 'Global'/'Chat'）
     * @param key     键位（如 'ctrl+c'）
     * @param action  动作标识（如 'app:interrupt'）
     */
    public record Binding(String context, String key, String action) {}

    /**
     * CC defaultBindings.ts DEFAULT_BINDINGS 的无条件绑定集（feature-gated 展开项除外，见下）.
     *
     * <p>平台相关键取值说明：CC 用 {@code MODE_CYCLE_KEY}（Windows 无 VT 模式 = meta+m，否则 shift+tab）
     * 与 {@code IMAGE_PASTE_KEY}（Windows = alt+v，否则 ctrl+v）。Java 后端生成 help 文本不绑定单一
     * 客户端平台，取非 Windows 便携默认（shift+tab / ctrl+v），登记为平台相关简化。
     *
     * <p>feature-gated 展开项（feature('KAIROS'|'KAIROS_BRIEF') 的 ctrl+shift+b→app:toggleBrief、
     * feature('QUICK_SEARCH') 的 app:globalSearch/app:quickOpen、feature('TERMINAL_PANEL') 的
     * meta+j→app:toggleTerminal、feature('MESSAGE_ACTIONS') 的 shift+up→chat:messageActions、
     * feature('VOICE_MODE') 的 space→voice:pushToTalk）不内嵌——对应动作在表格中按 CC
     * {@code info===undefined} 分支显示 {@code (none)} + 推断 context（等价 feature 关闭的 CC 生产 bundle）。
     * Scroll/MessageActions 上下文的 scroll:* / messageActions:* 动作不在 KEYBINDING_ACTIONS 内，
     * 不进入 actions table，故亦不内嵌。
     */
    private static final List<Binding> DEFAULT_BINDINGS = List.of(
        // Global
        new Binding("Global", "ctrl+c", "app:interrupt"),
        new Binding("Global", "ctrl+d", "app:exit"),
        new Binding("Global", "ctrl+l", "app:redraw"),
        new Binding("Global", "ctrl+t", "app:toggleTodos"),
        new Binding("Global", "ctrl+o", "app:toggleTranscript"),
        new Binding("Global", "ctrl+shift+o", "app:toggleTeammatePreview"),
        new Binding("Global", "ctrl+r", "history:search"),
        // Chat
        new Binding("Chat", "escape", "chat:cancel"),
        new Binding("Chat", "ctrl+x ctrl+k", "chat:killAgents"),
        new Binding("Chat", "shift+tab", "chat:cycleMode"),
        new Binding("Chat", "meta+p", "chat:modelPicker"),
        new Binding("Chat", "meta+o", "chat:fastMode"),
        new Binding("Chat", "meta+t", "chat:thinkingToggle"),
        new Binding("Chat", "enter", "chat:submit"),
        new Binding("Chat", "up", "history:previous"),
        new Binding("Chat", "down", "history:next"),
        new Binding("Chat", "ctrl+_", "chat:undo"),
        new Binding("Chat", "ctrl+shift+-", "chat:undo"),
        new Binding("Chat", "ctrl+x ctrl+e", "chat:externalEditor"),
        new Binding("Chat", "ctrl+g", "chat:externalEditor"),
        new Binding("Chat", "ctrl+s", "chat:stash"),
        new Binding("Chat", "ctrl+v", "chat:imagePaste"),
        // Autocomplete
        new Binding("Autocomplete", "tab", "autocomplete:accept"),
        new Binding("Autocomplete", "escape", "autocomplete:dismiss"),
        new Binding("Autocomplete", "up", "autocomplete:previous"),
        new Binding("Autocomplete", "down", "autocomplete:next"),
        // Settings
        new Binding("Settings", "escape", "confirm:no"),
        new Binding("Settings", "up", "select:previous"),
        new Binding("Settings", "down", "select:next"),
        new Binding("Settings", "k", "select:previous"),
        new Binding("Settings", "j", "select:next"),
        new Binding("Settings", "ctrl+p", "select:previous"),
        new Binding("Settings", "ctrl+n", "select:next"),
        new Binding("Settings", "space", "select:accept"),
        new Binding("Settings", "enter", "settings:close"),
        new Binding("Settings", "/", "settings:search"),
        new Binding("Settings", "r", "settings:retry"),
        // Confirmation
        new Binding("Confirmation", "y", "confirm:yes"),
        new Binding("Confirmation", "n", "confirm:no"),
        new Binding("Confirmation", "enter", "confirm:yes"),
        new Binding("Confirmation", "escape", "confirm:no"),
        new Binding("Confirmation", "up", "confirm:previous"),
        new Binding("Confirmation", "down", "confirm:next"),
        new Binding("Confirmation", "tab", "confirm:nextField"),
        new Binding("Confirmation", "space", "confirm:toggle"),
        new Binding("Confirmation", "shift+tab", "confirm:cycleMode"),
        new Binding("Confirmation", "ctrl+e", "confirm:toggleExplanation"),
        new Binding("Confirmation", "ctrl+d", "permission:toggleDebug"),
        // Tabs
        new Binding("Tabs", "tab", "tabs:next"),
        new Binding("Tabs", "shift+tab", "tabs:previous"),
        new Binding("Tabs", "right", "tabs:next"),
        new Binding("Tabs", "left", "tabs:previous"),
        // Transcript
        new Binding("Transcript", "ctrl+e", "transcript:toggleShowAll"),
        new Binding("Transcript", "ctrl+c", "transcript:exit"),
        new Binding("Transcript", "escape", "transcript:exit"),
        new Binding("Transcript", "q", "transcript:exit"),
        // HistorySearch
        new Binding("HistorySearch", "ctrl+r", "historySearch:next"),
        new Binding("HistorySearch", "escape", "historySearch:accept"),
        new Binding("HistorySearch", "tab", "historySearch:accept"),
        new Binding("HistorySearch", "ctrl+c", "historySearch:cancel"),
        new Binding("HistorySearch", "enter", "historySearch:execute"),
        // Task
        new Binding("Task", "ctrl+b", "task:background"),
        // ThemePicker
        new Binding("ThemePicker", "ctrl+t", "theme:toggleSyntaxHighlighting"),
        // Help
        new Binding("Help", "escape", "help:dismiss"),
        // Attachments
        new Binding("Attachments", "right", "attachments:next"),
        new Binding("Attachments", "left", "attachments:previous"),
        new Binding("Attachments", "backspace", "attachments:remove"),
        new Binding("Attachments", "delete", "attachments:remove"),
        new Binding("Attachments", "down", "attachments:exit"),
        new Binding("Attachments", "escape", "attachments:exit"),
        // Footer
        new Binding("Footer", "up", "footer:up"),
        new Binding("Footer", "ctrl+p", "footer:up"),
        new Binding("Footer", "down", "footer:down"),
        new Binding("Footer", "ctrl+n", "footer:down"),
        new Binding("Footer", "right", "footer:next"),
        new Binding("Footer", "left", "footer:previous"),
        new Binding("Footer", "enter", "footer:openSelected"),
        new Binding("Footer", "escape", "footer:clearSelection"),
        // MessageSelector
        new Binding("MessageSelector", "up", "messageSelector:up"),
        new Binding("MessageSelector", "down", "messageSelector:down"),
        new Binding("MessageSelector", "k", "messageSelector:up"),
        new Binding("MessageSelector", "j", "messageSelector:down"),
        new Binding("MessageSelector", "ctrl+p", "messageSelector:up"),
        new Binding("MessageSelector", "ctrl+n", "messageSelector:down"),
        new Binding("MessageSelector", "ctrl+up", "messageSelector:top"),
        new Binding("MessageSelector", "shift+up", "messageSelector:top"),
        new Binding("MessageSelector", "meta+up", "messageSelector:top"),
        new Binding("MessageSelector", "shift+k", "messageSelector:top"),
        new Binding("MessageSelector", "ctrl+down", "messageSelector:bottom"),
        new Binding("MessageSelector", "shift+down", "messageSelector:bottom"),
        new Binding("MessageSelector", "meta+down", "messageSelector:bottom"),
        new Binding("MessageSelector", "shift+j", "messageSelector:bottom"),
        new Binding("MessageSelector", "enter", "messageSelector:select"),
        // DiffDialog
        new Binding("DiffDialog", "escape", "diff:dismiss"),
        new Binding("DiffDialog", "left", "diff:previousSource"),
        new Binding("DiffDialog", "right", "diff:nextSource"),
        new Binding("DiffDialog", "up", "diff:previousFile"),
        new Binding("DiffDialog", "down", "diff:nextFile"),
        new Binding("DiffDialog", "enter", "diff:viewDetails"),
        // ModelPicker
        new Binding("ModelPicker", "left", "modelPicker:decreaseEffort"),
        new Binding("ModelPicker", "right", "modelPicker:increaseEffort"),
        // Select
        new Binding("Select", "up", "select:previous"),
        new Binding("Select", "down", "select:next"),
        new Binding("Select", "j", "select:next"),
        new Binding("Select", "k", "select:previous"),
        new Binding("Select", "ctrl+n", "select:next"),
        new Binding("Select", "ctrl+p", "select:previous"),
        new Binding("Select", "enter", "select:accept"),
        new Binding("Select", "escape", "select:cancel"),
        // Plugin
        new Binding("Plugin", "space", "plugin:toggle"),
        new Binding("Plugin", "i", "plugin:install")
    );

    /** 保留快捷键（CC reservedShortcuts.ts {@code ReservedShortcut}：key/reason/severity）. */
    private record ReservedShortcut(String key, String reason, String severity) {}

    /** CC original: NON_REBINDABLE (reservedShortcuts.ts:16-33). */
    private static final List<ReservedShortcut> NON_REBINDABLE = List.of(
        new ReservedShortcut("ctrl+c", "Cannot be rebound - used for interrupt/exit (hardcoded)", "error"),
        new ReservedShortcut("ctrl+d", "Cannot be rebound - used for exit (hardcoded)", "error"),
        new ReservedShortcut("ctrl+m", "Cannot be rebound - identical to Enter in terminals (both send CR)", "error")
    );

    /** CC original: TERMINAL_RESERVED (reservedShortcuts.ts:43-54). */
    private static final List<ReservedShortcut> TERMINAL_RESERVED = List.of(
        new ReservedShortcut("ctrl+z", "Unix process suspend (SIGTSTP)", "warning"),
        new ReservedShortcut("ctrl+\\", "Terminal quit signal (SIGQUIT)", "error")
    );

    /** CC original: MACOS_RESERVED (reservedShortcuts.ts:59-67). */
    private static final List<ReservedShortcut> MACOS_RESERVED = List.of(
        new ReservedShortcut("cmd+c", "macOS system copy", "error"),
        new ReservedShortcut("cmd+v", "macOS system paste", "error"),
        new ReservedShortcut("cmd+x", "macOS system cut", "error"),
        new ReservedShortcut("cmd+q", "macOS quit application", "error"),
        new ReservedShortcut("cmd+w", "macOS close window/tab", "error"),
        new ReservedShortcut("cmd+tab", "macOS app switcher", "error"),
        new ReservedShortcut("cmd+space", "macOS Spotlight", "error")
    );

    /** CC original: inferContextFromAction 的 prefix → context 映射 (keybindings.ts:63-82)，缺省 'Unknown'. */
    private static final Map<String, String> PREFIX_TO_CONTEXT = Map.ofEntries(
        Map.entry("app", "Global"),
        Map.entry("history", "Global or Chat"),
        Map.entry("chat", "Chat"),
        Map.entry("autocomplete", "Autocomplete"),
        Map.entry("confirm", "Confirmation"),
        Map.entry("tabs", "Tabs"),
        Map.entry("transcript", "Transcript"),
        Map.entry("historySearch", "HistorySearch"),
        Map.entry("task", "Task"),
        Map.entry("theme", "ThemePicker"),
        Map.entry("help", "Help"),
        Map.entry("attachments", "Attachments"),
        Map.entry("footer", "Footer"),
        Map.entry("messageSelector", "MessageSelector"),
        Map.entry("diff", "DiffDialog"),
        Map.entry("modelPicker", "ModelPicker"),
        Map.entry("select", "Select"),
        Map.entry("permission", "Confirmation")
    );

    // ════════════════════════════════════════════════════════════════════════
    // JSON 示例（CC keybindings.ts FILE_FORMAT_EXAMPLE / UNBIND_EXAMPLE / REBIND_EXAMPLE / CHORD_EXAMPLE，
    // 经 jsonStringify(_, null, 2) 2 空格缩进）
    // ════════════════════════════════════════════════════════════════════════

    private static final String FILE_FORMAT_JSON = """
        {
          "$schema": "https://www.schemastore.org/claude-code-keybindings.json",
          "$docs": "https://code.claude.com/docs/en/keybindings",
          "bindings": [
            {
              "context": "Chat",
              "bindings": {
                "ctrl+e": "chat:externalEditor"
              }
            }
          ]
        }
        """;

    private static final String UNBIND_JSON = """
        {
          "context": "Chat",
          "bindings": {
            "ctrl+s": null
          }
        }
        """;

    private static final String REBIND_JSON = """
        {
          "context": "Chat",
          "bindings": {
            "ctrl+g": null,
            "ctrl+e": "chat:externalEditor"
          }
        }
        """;

    private static final String CHORD_JSON = """
        {
          "context": "Global",
          "bindings": {
            "ctrl+k ctrl+t": "app:toggleTodos"
          }
        }
        """;

    // ════════════════════════════════════════════════════════════════════════
    // 纯文本段（CC SECTION_INTRO / KEYSTROKE_SYNTAX / INTERACTION / BEHAVIORAL_RULES）
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: SECTION_INTRO (keybindings.ts:149-160). */
    private static final String SECTION_INTRO = """
        # Keybindings Skill

        Create or modify `~/.nexusai/keybindings.json` to customize keyboard shortcuts.

        ## CRITICAL: Read Before Write

        **Always read `~/.nexusai/keybindings.json` first** (it may not exist yet). Merge changes with existing bindings — never replace the entire file.

        - Use **Edit** tool for modifications to existing files
        - Use **Write** tool only if the file does not exist yet""";

    /** CC original: SECTION_KEYSTROKE_SYNTAX (keybindings.ts:172-186). */
    private static final String SECTION_KEYSTROKE_SYNTAX = """
        ## Keystroke Syntax

        **Modifiers** (combine with `+`):
        - `ctrl` (alias: `control`)
        - `alt` (aliases: `opt`, `option`) — note: `alt` and `meta` are identical in terminals
        - `shift`
        - `meta` (aliases: `cmd`, `command`)

        **Special keys**: `escape`/`esc`, `enter`/`return`, `tab`, `space`, `backspace`, `delete`, `up`, `down`, `left`, `right`

        **Chords**: Space-separated keystrokes, e.g. `ctrl+k ctrl+s` (1-second timeout between keystrokes)

        **Examples**: `ctrl+shift+p`, `alt+enter`, `ctrl+k ctrl+n`""";

    /** CC original: SECTION_INTERACTION (keybindings.ts:198-204). */
    private static final String SECTION_INTERACTION = """
        ## How User Bindings Interact with Defaults

        - User bindings are **additive** — they are appended after the default bindings
        - To **move** a binding to a different key: unbind the old key (`null`) AND add the new binding
        - A context only needs to appear in the user's file if they want to change something in that context""";

    /** CC original: SECTION_BEHAVIORAL_RULES (keybindings.ts:221-229). */
    private static final String SECTION_BEHAVIORAL_RULES = """
        ## Behavioral Rules

        1. Only include contexts the user wants to change (minimal overrides)
        2. Validate that actions and contexts are from the known lists below
        3. Warn the user proactively if they choose a key that conflicts with reserved shortcuts or common tools like tmux (`ctrl+b`) and screen (`ctrl+a`)
        4. When adding a new binding for an existing action, the new binding is additive (existing default still works unless explicitly unbound)
        5. To fully replace a default binding, unbind the old key AND add the new one""";

    private final Consumer<BundledSkillDefinition> registrar;

    /**
     * 构造器：注入注册回调 · CC keybindings.ts {@code registerKeybindingsSkill()} 是模块级函数、无构造器，
     * 数据从 schema.ts / defaultBindings.ts / reservedShortcuts.ts 以模块级数组导入；Java 等价为静态常量内嵌
     * （CONTEXTS / ACTIONS / DEFAULT_BINDINGS / Reserved 三组），registrar Consumer 即 CC
     * {@code registerBundledSkill} 的注册等价物（CC keybindings.ts:292-299）。
     */
    public KeybindingsSkill(Consumer<BundledSkillDefinition> registrar) {
        this.registrar = registrar == null ? def -> {} : registrar;
    }

    /** 无参构造（测试用），等价 1 参传 null. */
    public KeybindingsSkill() {
        this(null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // isEnabled 门控（CC keybindings.ts:299 isKeybindingCustomizationEnabled）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * keybindings 自定义启用判定 · CC original:
     * {@code isKeybindingCustomizationEnabled} (loadUserBindings.ts:41-46)：
     * <pre>{@code
     * return getFeatureValue_CACHED_MAY_BE_STALE('tengu_keybinding_customization_release', false)
     * }</pre>
     *
     * <p>即 GrowthBook 特性门 {@code tengu_keybinding_customization_release}，<b>默认 false</b>（外部用户
     * 不可自定义键位）。Java 无 GrowthBook，用配置/环境等价：
     * {@code nexusai.feature.keybinding-customization} 系统属性 → {@code NEXUSAI_FEATURE_KEYBINDING_CUSTOMIZATION}
     * 环境变量（truthy 判定同 {@link TaskSystemConfig#isEnvTruthy}）；均未设 → false（对齐 CC GB 默认）。
     *
     * @return true = keybindings 自定义启用（CC 默认 false）
     */
    public static boolean isKeybindingCustomizationEnabled() {
        String sysProp = System.getProperty("nexusai.feature.keybinding-customization");
        if (sysProp != null && !sysProp.isBlank()) {
            return TaskSystemConfig.isEnvTruthy(sysProp);
        }
        String envVal = System.getenv("NEXUSAI_FEATURE_KEYBINDING_CUSTOMIZATION");
        if (envVal != null && !envVal.isBlank()) {
            return TaskSystemConfig.isEnvTruthy(envVal);
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 + prompt 生成
    // ════════════════════════════════════════════════════════════════════════

    /** CC registerKeybindingsSkill 主链（keybindings.ts:292-327）· 统一产出 BundledSkillDefinition. */
    public void registerSkill() {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            // [T3/#21] description 含路径文本 → 动态 appName（决策 D1/D6）
            SKILL_DESCRIPTION.replace(".nexusai", "." + NexusaiPaths.getAppName()),
            null,   // aliases（CC 无）
            null,   // whenToUse（CC 无）
            null,   // argumentHint（CC 无）
            List.of("Read"),   // allowedTools (CC keybindings.ts:297)
            null,   // model
            null,   // disableModelInvocation（CC undefined → default false）
            false,  // userInvocable (CC keybindings.ts:298)
            KeybindingsSkill::isKeybindingCustomizationEnabled,   // isEnabled (CC keybindings.ts:299)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> formatPrompt(args)
        );
        if (log.isDebugEnabled()) {
            log.debug("[KeybindingsSkill] 注册 keybindings-help：userInvocable=false allowedTools=[Read] "
                + "isEnabled={}（CC keybindings.ts:297-299）",
                isKeybindingCustomizationEnabled());
        }
        registrar.accept(def);
    }

    /** CC getPromptForCommand（keybindings.ts:300-325）· 11 段完整 prompt，段间以 '\n\n' 连接. */
    public List<PromptBlock> formatPrompt(String args) {
        String contextsTable = generateContextsTable();
        String actionsTable = generateActionsTable();
        String reservedShortcuts = generateReservedShortcuts();

        List<String> sections = new ArrayList<>();
        sections.add(SECTION_INTRO);
        sections.add(sectionFileFormat());
        sections.add(SECTION_KEYSTROKE_SYNTAX);
        sections.add(sectionUnbinding());
        sections.add(SECTION_INTERACTION);
        sections.add(sectionCommonPatterns());
        sections.add(SECTION_BEHAVIORAL_RULES);
        sections.add(sectionDoctor());
        sections.add("## Reserved Shortcuts\n\n" + reservedShortcuts);
        sections.add("## Available Contexts\n\n" + contextsTable);
        sections.add("## Available Actions\n\n" + actionsTable);
        if (args != null && !args.isEmpty()) {
            sections.add("## User Request\n\n" + args);
        }

        String prompt = String.join("\n\n", sections);
        // [T3/#21] prompt 文本 .nexusai → 动态 appName（决策 D1/D6）：keybindings 指引目录随 appName 联动
        prompt = prompt.replace(".nexusai", "." + NexusaiPaths.getAppName());
        if (log.isDebugEnabled()) {
            log.debug("[KeybindingsSkill] 生成 keybindings prompt：{} 段，{} 上下文，{} 动作，长度 {}",
                sections.size(), CONTEXTS.size(), ACTIONS.size(), prompt.length());
        }
        return List.of(PromptBlock.text(prompt));
    }

    /** CC generateContextsTable（keybindings.ts:20-28）· 纯函数，从 CONTEXTS 常量建表. */
    public String generateContextsTable() {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : CONTEXTS.entrySet()) {
            rows.add(new String[]{"`" + e.getKey() + "`", e.getValue()});
        }
        return markdownTable(new String[]{"Context", "Description"}, rows);
    }

    /** CC generateActionsTable（keybindings.ts:33-56）· 纯函数，从 DEFAULT_BINDINGS + ACTIONS 常量建表. */
    public String generateActionsTable() {
        // action -> { keys[], context } lookup（CC actionInfo）
        Map<String, List<String>> keysByAction = new LinkedHashMap<>();
        Map<String, String> contextByAction = new LinkedHashMap<>();
        for (Binding b : DEFAULT_BINDINGS) {
            keysByAction.computeIfAbsent(b.action(), k -> new ArrayList<>()).add(b.key());
            contextByAction.putIfAbsent(b.action(), b.context());
        }

        List<String[]> rows = new ArrayList<>();
        for (String action : ACTIONS) {
            List<String> keys = keysByAction.get(action);
            String keysCell;
            String contextCell;
            if (keys != null && !keys.isEmpty()) {
                keysCell = keys.stream().map(k -> "`" + k + "`")
                    .collect(java.util.stream.Collectors.joining(", "));
                contextCell = contextByAction.get(action);
            } else {
                keysCell = "(none)";
                contextCell = inferContextFromAction(action);
            }
            rows.add(new String[]{"`" + action + "`", keysCell, contextCell});
        }
        return markdownTable(new String[]{"Action", "Default Key(s)", "Context"}, rows);
    }

    /** CC generateReservedShortcuts（keybindings.ts:89-112）· 三段保留快捷键清单. */
    public String generateReservedShortcuts() {
        List<String> lines = new ArrayList<>();
        lines.add("### Non-rebindable (errors)");
        for (ReservedShortcut s : NON_REBINDABLE) {
            lines.add("- `" + s.key() + "` — " + s.reason());
        }
        lines.add("");
        lines.add("### Terminal reserved (errors/warnings)");
        for (ReservedShortcut s : TERMINAL_RESERVED) {
            lines.add("- `" + s.key() + "` — " + s.reason()
                + " (" + ("error".equals(s.severity()) ? "will not work" : "may conflict") + ")");
        }
        lines.add("");
        lines.add("### macOS reserved (errors)");
        for (ReservedShortcut s : MACOS_RESERVED) {
            lines.add("- `" + s.key() + "` — " + s.reason());
        }
        return String.join("\n", lines);
    }

    /** CC inferContextFromAction（keybindings.ts:61-84）· 动作前缀 → context，缺省 'Unknown'. */
    static String inferContextFromAction(String action) {
        String prefix = action.split(":")[0];
        return PREFIX_TO_CONTEXT.getOrDefault(prefix, "Unknown");
    }

    // ── 组合段（含 JSON 示例/表格，动态拼装）──

    /** CC SECTION_FILE_FORMAT（keybindings.ts:162-170）. */
    private static String sectionFileFormat() {
        return "## File Format\n\n```json\n" + FILE_FORMAT_JSON
            + "\n```\n\nAlways include the `$schema` and `$docs` fields.";
    }

    /** CC SECTION_UNBINDING（keybindings.ts:188-196）. */
    private static String sectionUnbinding() {
        return "## Unbinding Default Shortcuts\n\n"
            + "Set a key to `null` to remove its default binding:\n\n```json\n"
            + UNBIND_JSON + "\n```";
    }

    /** CC SECTION_COMMON_PATTERNS（keybindings.ts:206-219）. */
    private static String sectionCommonPatterns() {
        return "## Common Patterns\n\n### Rebind a key\n"
            + "To change the external editor shortcut from `ctrl+g` to `ctrl+e`:\n```json\n"
            + REBIND_JSON + "\n```\n\n### Add a chord binding\n```json\n"
            + CHORD_JSON + "\n```";
    }

    /** CC SECTION_DOCTOR（keybindings.ts:231-290）. */
    private static String sectionDoctor() {
        List<String[]> issueRows = List.of(
            new String[]{"`keybindings.json must have a \"bindings\" array`", "Missing wrapper object",
                "Wrap bindings in `{ \"bindings\": [...] }`"},
            new String[]{"`\"bindings\" must be an array`", "`bindings` is not an array",
                "Set `\"bindings\"` to an array: `[{ context: ..., bindings: ... }]`"},
            new String[]{"`Unknown context \"X\"`", "Typo or invalid context name",
                "Use exact context names from the Available Contexts table"},
            new String[]{"`Duplicate key \"X\" in Y bindings`", "Same key defined twice in one context",
                "Remove the duplicate; JSON uses only the last value"},
            new String[]{"`\"X\" may not work: ...`", "Key conflicts with terminal/OS reserved shortcut",
                "Choose a different key (see Reserved Shortcuts section)"},
            new String[]{"`Could not parse keystroke \"X\"`", "Invalid key syntax",
                "Check syntax: use `+` between modifiers, valid key names"},
            new String[]{"`Invalid action for \"X\"`", "Action value is not a string or null",
                "Actions must be strings like `\"app:help\"` or `null` to unbind"}
        );
        return "## Validation with /doctor\n\n"
            + "The `/doctor` command includes a \"Keybinding Configuration Issues\" section that validates `~/.nexusai/keybindings.json`.\n\n"
            + "### Common Issues and Fixes\n\n"
            + markdownTable(new String[]{"Issue", "Cause", "Fix"}, issueRows)
            + "\n\n### Example /doctor Output\n\n```\n"
            + "Keybinding Configuration Issues\n"
            + "Location: ~/.nexusai/keybindings.json\n"
            + "  └ [Error] Unknown context \"chat\"\n"
            + "    → Valid contexts: Global, Chat, Autocomplete, ...\n"
            + "  └ [Warning] \"ctrl+c\" may not work: Terminal interrupt (SIGINT)\n"
            + "```\n\n"
            + "**Errors** prevent bindings from working and must be fixed. **Warnings** indicate potential conflicts but the binding may still work.";
    }

    /** CC markdownTable（keybindings.ts:332-339）· header + '---' 分隔行 + rows，join('\n') 末行无尾随换行. */
    private static String markdownTable(String[] headers, List<String[]> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("| " + String.join(" | ", headers) + " |");
        List<String> separator = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            separator.add("---");
        }
        lines.add("| " + String.join(" | ", separator) + " |");
        for (String[] row : rows) {
            lines.add("| " + String.join(" | ", row) + " |");
        }
        return String.join("\n", lines);
    }
}
