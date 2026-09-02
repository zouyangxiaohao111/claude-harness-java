package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.settings.SettingsCache;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SUB-27 Rework] A10 guide 动态 getSystemPrompt 聚焦测试.
 *
 * <p>WHY: {@link ClaudeCodeGuideAgentDef#buildGuideSystemPrompt} 是对齐 CC
 * claudeCodeGuideAgent.ts:121-204 getSystemPrompt({toolUseContext}) 的新增动态逻辑（5 段上下文 +
 * config 头块 + 空回退 + settings JSON + feedback）。此前的注册类测试
 * {@link ClaudeCodeGuideAgentRegistrationTest} 只覆盖 agentType/model/permissionMode/tools，
 * 零覆盖本逻辑——本测试锁定 A10 交付物的行为意图（每条断言标注 CC 行号）。
 *
 * <p>覆盖场景（对应反思 REWORK 清单）：
 * <ol>
 *   <li>5 段各自渲染与过滤语义（custom skills :128-136 / custom agents :139-150 / MCP :153-159 /
 *       plugin :162-170 / settings :173-180）</li>
 *   <li>contextSections 空 → 返回 basePromptWithFeedback（CC :202-203 回退）</li>
 *   <li>config 头块文案与拼接顺序（CC :188-200）</li>
 *   <li>settings JSON 序列化（含非 Map 回退 :173-180 + readSessionSettings 兜底）</li>
 *   <li>feedback guideline /feedback 文案（CC :95）</li>
 * </ol>
 */
class ClaudeCodeGuideAgentDefTest {

    @AfterEach
    void tearDown() {
        // 还原 ThreadLocal SettingsCache，避免污染同线程其它测试
        SettingsCache.instance().resetSettingsCache();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 段 1：Custom skills（CC :128-136）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("段1: 仅 type='prompt' 命令进 custom skills 段（CC :128 过滤）")
    void section1_custom_skills_renders_only_prompt_commands() {
        // WHY: CC :128 commands.filter(cmd => cmd.type === 'prompt') —— 过滤只看 type 不看 source，
        // 故 plugin prompt 命令也出现在 custom skills 段；type='local' 命令被排除。
        List<Command> commands = List.of(
            promptCommand("my-skill", "My custom skill desc", CommandSource.USER),
            promptCommand("plug-skill", "Plugin skill desc", CommandSource.PLUGIN),
            localCommand("reset", "Local no-side-effect command"));

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(commands, List.of(), List.of(), Map.of());

        assertThat(out)
            .contains("**Available custom skills in this project:**")
            .contains("- /my-skill: My custom skill desc")
            .contains("- /plug-skill: Plugin skill desc")   // plugin prompt 命令同样命中 CC :128
            .doesNotContain("- /reset");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 段 2：Custom agents（CC :139-150）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("段2: source='built-in' 的 agent 被排除（CC :141 过滤）")
    void section2_custom_agents_excludes_builtin_sources() {
        // WHY: CC :140-142 activeAgents.filter(a => a.source !== 'built-in') —— 内置 agent 不算
        // "custom agents"，仅非 built-in 源进入渲染列表。
        List<AgentDefinition> agents = List.of(
            builtInAgent("statusline", "setup"),
            customAgent("my-custom", "Custom agent desc"));

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(List.of(), agents, List.of(), Map.of());

        assertThat(out)
            .contains("**Available custom agents configured:**")
            .contains("- my-custom: Custom agent desc")
            .doesNotContain("statusline");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 段 3：MCP servers（CC :153-159）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("段3: mcpClientNames 逐个渲染（CC :155-158）")
    void section3_mcp_servers_renders_client_names() {
        // WHY: CC :153-154 mcpClients 非空才 push —— MCP 段与 agents/settings 无关，独立可测。
        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(
            List.of(), List.of(), List.of("mcp-server-a", "mcp-server-b"), Map.of());

        assertThat(out)
            .contains("**Configured MCP servers:**")
            .contains("- mcp-server-a\n- mcp-server-b");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 段 4：Plugin commands（CC :162-170）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("段4: 仅 source='plugin' 的 prompt 命令进 plugin skills 段（CC :162-164）")
    void section4_plugin_commands_filters_source_plugin() {
        // WHY: CC :162-164 filter(cmd => cmd.type === 'prompt' && cmd.source === 'plugin') —— 双条件
        // 收紧到 plugin 源；user prompt 命令只出现在段 1，不进入 plugin 段。
        List<Command> commands = List.of(
            promptCommand("my-skill", "User skill desc", CommandSource.USER),
            promptCommand("plug-skill", "Plugin skill desc", CommandSource.PLUGIN));

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(commands, List.of(), List.of(), Map.of());

        assertThat(out)
            .contains("**Available plugin skills:**\n- /plug-skill: Plugin skill desc")
            .doesNotContain("**Available plugin skills:**\n- /my-skill");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 段 5：User settings（CC :173-180）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("段5: settings 序列化为 pretty JSON（CC :176 jsonStringify(settings, null, 2)）")
    void section5_settings_serializes_pretty_json() {
        // WHY: CC :174-180 Object.keys(settings).length > 0 时把 settings 以 jsonStringify(,null,2)
        // 渲染进 ```json``` 块 —— web 端需以可读 JSON 呈现用户配置。
        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(
            List.of(), List.of(), List.of(), Map.of("model", "haiku", "theme", "dark"));

        assertThat(out)
            .contains("**User's settings.json:**")
            .contains("```json")
            .contains("\"model\" : \"haiku\"")       // Jackson 默认 pretty printer（对齐 ,null,2）
            .contains("\"theme\" : \"dark\"");
    }

    @Test
    @DisplayName("段5: settings JSON 序列化失败 → toString 回退（toSettingsJson 兜底）")
    void section5_settings_serialization_failure_falls_back_to_toString() {
        // WHY: toSettingsJson 对不可序列化值 catch 后回退 String.valueOf(settings)（实现 :312-320），
        // 保证动态 prompt 拼接永不因 settings 内容抛异常（CC jsonStringify 无此兜底，Java 防御性增强）。
        Map<String, Object> badSettings = Map.of("bad", new Object() {
            @SuppressWarnings("unused")
            public String getBoom() {
                throw new IllegalStateException("cannot-serialize");
            }
        });

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(List.of(), List.of(), List.of(), badSettings);

        assertThat(out)
            .contains("**User's settings.json:**")
            .contains("```json")
            .contains("{bad=");   // 回退为 Map.toString() 形式而非崩溃
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 空回退 + config 头块（CC :188-203）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空回退: contextSections 空 → 返回 basePromptWithFeedback（CC :202-203）")
    void empty_context_sections_returns_base_prompt_with_feedback() {
        // WHY: CC :202-203 "Return the base prompt if no context to add" —— 无任何动态上下文时
        // 不得输出 "# User's Current Configuration" 头块，只回退 base + feedback 两行拼接。
        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(List.of(), List.of(), List.of(), Map.of());

        String expected = ClaudeCodeGuideAgentDef.buildBaseSystemPrompt()
            + "\n"
            + ClaudeCodeGuideAgentDef.getFeedbackGuideline();
        assertThat(out)
            .isEqualTo(expected)
            .doesNotContain("# User's Current Configuration");
    }

    @Test
    @DisplayName("config 头块: 文案 + 拼接顺序 base→feedback→头块→段→收尾（CC :188-200）")
    void config_header_block_copy_and_concatenation_order() {
        // WHY: CC :189-199 模板顺序固定 —— basePromptWithFeedback + 空行 + '---' + 头块标题 +
        // 引导句 + contextSections.join('\n\n') + 收尾句；顺序错位会破坏模型对配置的感知。
        List<Command> commands = List.of(promptCommand("my-skill", "My custom skill desc", CommandSource.USER));

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(commands, List.of(), List.of(), Map.of());

        String section = "**Available custom skills in this project:**\n- /my-skill: My custom skill desc";
        String expected = ClaudeCodeGuideAgentDef.buildBaseSystemPrompt()
            + "\n"
            + ClaudeCodeGuideAgentDef.getFeedbackGuideline()
            + "\n\n---\n\n# User's Current Configuration\n\n"
            + "The user has the following custom setup in their environment:\n\n"
            + section
            + "\n\nWhen answering questions, consider these configured features and proactively suggest them when relevant.";
        assertThat(out).isEqualTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // feedback guideline（CC :89-96）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("feedback: 无 3P 通道 → 恒 /feedback 文案（CC :95）")
    void feedback_guideline_uses_feedback_branch() {
        // WHY: CC :92-95 isUsing3PServices() 为真才走 ISSUES_EXPLAINER（:93），Java 无
        // bedrock/vertex/foundry 3P 通道 → 恒 /feedback（:95）；锁定文案防误删。
        String expected = "- When you cannot find an answer or the feature doesn't exist, direct the user to use /feedback to report a feature request or bug";
        assertThat(ClaudeCodeGuideAgentDef.getFeedbackGuideline()).isEqualTo(expected);

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt(List.of(), List.of(), List.of(), Map.of());
        assertThat(out).contains("/feedback to report a feature request or bug");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 非 Map settings 回退（readSessionSettings）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非 Map settings 缓存 → settings 段省略（readSessionSettings 兜底）")
    void non_map_settings_cache_omits_settings_section() {
        // WHY: readSessionSettings（实现 :299-305）对非 Map / null 缓存返回空 Map —— CC :174
        // Object.keys(settings).length === 0 时 settings 段省略；经无参入口验证生产路径降级行为。
        SettingsCache.instance().setSessionSettingsCache("not-a-map");

        String out = ClaudeCodeGuideAgentDef.buildGuideSystemPrompt();

        // 生产路径（无参入口）数据源：内置命令含 /init(type='prompt') → 段 1 渲染；
        // settings 缓存非 Map → 段 5 省略。
        assertThat(out)
            .contains("**Available custom skills in this project:**")
            .contains("- /init:")
            .doesNotContain("**User's settings.json:**");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static Command promptCommand(String name, String description, CommandSource source) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        c.setDescription(description);
        c.setSource(source);
        return c;
    }

    private static Command localCommand(String name, String description) {
        Command c = new Command();
        c.setName(name);
        c.setType("local");
        c.setDescription(description);
        c.setSource(CommandSource.BUILTIN);
        return c;
    }

    private static AgentDefinition builtInAgent(String type, String whenToUse) {
        return AgentDefinition.BuiltInAgentDefinition.builder(type, whenToUse, (m, d) -> "").build();
    }

    private static AgentDefinition customAgent(String type, String whenToUse) {
        return AgentDefinition.CustomAgentDefinition.builder(type, whenToUse, "projectSettings", "body").build();
    }
}
