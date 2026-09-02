package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H1] 配置驱动 hook 主链路 · HookMatcherEngine · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:1603-1874} (getMatchingHooks) +
 * {@code :1346-1381} (matchesPattern).
 *
 * <p>WHY (规则九 · 测试验证意图): 本测试验证 <b>配置驱动主链路</b> 的意图 — settings.json
 * 的 hook 配置必须能:
 * <ol>
 *   <li>从事件提取 matchQuery (PreToolUse/PostToolUse 用 tool_name), 否则 matcher 永远匹配不到</li>
 *   <li>matcher 匹配命中 (精确 / 正则 / 管道列表)</li>
 *   <li>if 条件仅在有值时过滤 (null/空串 = 无限制)</li>
 *   <li>同 (event, matcher) 重复注册去重 (if 是身份一部分)</li>
 *   <li>从快照一路走到引擎命中 (loadFromSource → capture → getMatchingHooks)</li>
 * </ol>
 *
 * <p>不依赖 Spring 容器: 手动构造 {@link HooksSettings}(test policy supplier) /
 * {@link HooksConfigSnapshot} / {@link HookMatcherEngine}.
 *
 * @since Session H1 (P0)
 */
@DisplayName("[H1] HookMatcherEngine 配置驱动主链路对齐 CC")
class HookMatcherEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── 构造 helper ─────────────────────────────────────────────────────────

    /** 无 policy 限制的 engine (HooksSettings policy supplier 恒返回 null). */
    private HookMatcherEngine newEngine(HooksSettings settings) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        // [G3] if 内容匹配迁移到接口：Bash 内容匹配由 BashTool.preparePermissionMatcher 承担
        //       （CC hooks.ts:1407-1419 工具驱动，无集中回退）→ 注入 ToolRegistry + BashTool。
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        engine.setToolRegistry(registry);
        // [H-WF2-01] safeParse 门禁（CC hooks.ts:1405-1409）：复用 ToolInputValidator
        //   （与工具执行链路同一 validator）校验 tool_input 后才取 matcher。
        engine.setInputValidator(new ToolInputValidator());
        return engine;
    }

    /** 追加工具（如 Read，其 schema 声明 additionalProperties:false → 未知键被拒绝）。 */
    private HookMatcherEngine newEngineWithTools(HooksSettings settings, Tool... tools) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        ToolRegistry registry = new ToolRegistry();
        for (Tool t : tools) {
            registry.register(t);
        }
        engine.setToolRegistry(registry);
        engine.setInputValidator(new ToolInputValidator());
        return engine;
    }

    // ── 1. 正向: PreToolUse tool_name 提取 + matcher 命中 ──────────────────

    @Test
    @DisplayName("1. PreToolUse 事件从 toolName 提取 matchQuery + matcher 命中")
    void preToolUse_extractsToolName_andMatcherHits() {
        // 意图: PreToolUse 必须从 tool_name 提取 matchQuery, 否则 matcher "Bash" 永远匹配不到,
        //       配置驱动 hook 等于没配 (探查 §A 3.1-2).
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        HookEvent event = HookEvent.toolPre("Bash", null, "s1", null);
        List<MatchedHook> matched = engine.getMatchingHooks(event);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).hookSource()).isEqualTo("settings");
        assertThat(matched.get(0).hook()).isInstanceOf(CommandHook.class);
    }

    // ── 2. 正向: PostToolUse 用同一 tool_name 字段 ──────────────────────────

    @Test
    @DisplayName("2. PostToolUse 事件从 toolName 提取 matchQuery (与 PreToolUse 同一字段)")
    void postToolUse_extractsToolName_sameFieldAsPreToolUse() {
        // 意图: CC switch 中 PostToolUse 与 PreToolUse 共用 tool_name 分支 (hooks.ts:1616-1622),
        //       若 PostToolUse 漏接 tool_name, matcher 配置对 PostToolUse 全部失效.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.POST_TOOL_USE,
                new PromptHook("check", null, null, null, null, null),
                "Write", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        HookEvent event = HookEvent.toolPost("Write", null, null, "s1", null);
        List<MatchedHook> matched = engine.getMatchingHooks(event);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).hook()).isInstanceOf(PromptHook.class);
    }

    // ── 3. 反向: matcher 不匹配 → 过滤 ─────────────────────────────────────

    @Test
    @DisplayName("3. matcher 不匹配 → hook 被过滤")
    void matcher_mismatch_filtersHook() {
        // 意图: matcher 是事件粒度的过滤器 — tool_name=Bash 时 matcher "Edit" 不应命中,
        //       否则 Bash 工具会误触发 Edit 专属 hook.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Edit", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        HookEvent event = HookEvent.toolPre("Bash", null, "s1", null);
        assertThat(engine.getMatchingHooks(event)).isEmpty();
    }

    // ── 4. 边界: if null / 空串 → 不过滤 ──────────────────────────────────

    @Test
    @DisplayName("4. if 为 null / 空串 → 不过滤 (CC: if 仅在有值时过滤)")
    void ifNullOrEmpty_doesNotFilter() {
        // 意图: CC hooks.ts:1824-1827 — if 无值 (null/空) 直接放行; 只有非空 if 才参与过滤,
        //       否则旧配置 (无 if 字段) 会全被过滤掉.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo a", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo b", "", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        HookEvent event = HookEvent.toolPre("Bash", null, "s1", null);
        assertThat(engine.getMatchingHooks(event)).hasSize(2);
    }

    // ── 5. 边界: 去重 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("5. 同 (event, matcher) 重复注册 → 去重只保留一条; 不同 if 视为不同 hook")
    void sameEventAndMatcher_dedupKeepsLast_ifIsIdentity() throws Exception {
        // 意图: CC hooks.ts:1742-1806 — 同 event+matcher 下相同 command+if 的重复 hook 只跑一次
        //       (否则同一个 shell 命令被重复执行); 但 if 是身份一部分 (hooksSettings.ts:43-44),
        //       相同 command 不同 if 是不同 hook, 不能去重.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        HookEvent event = HookEvent.toolPre("Bash", null, "s1", null);
        assertThat(engine.getMatchingHooks(event)).hasSize(1);

        // 不同 if → 2 条 (if 是身份一部分, 不可去重) — 提供匹配输入让两个 if 都通过过滤
        JsonNode input = mapper.readTree("{\"command\":\"git status\"}");
        HooksSettings settings2 = new HooksSettings(key -> null);
        settings2.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git status)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine2 = newEngine(settings2);
        assertThat(engine2.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(2);
    }

    // ── 6. 集成: 快照链路 ─────────────────────────────────────────────────

    @Test
    @DisplayName("6. 集成: loadFromSource → getAllHooks → capture → 快照 → 引擎命中")
    void integration_loadFromSource_toSnapshot_toEngine() {
        // 意图: 探查 §D 2.4-1 确认 loadFromSource 无调用方 → 数据通路全断. 本测试验证修复后
        //       整条链路: loadFromSource 写入 bySource → getAllHooks 非空 → capture 快照 →
        //       getHooksConfigFromSnapshot 非空 → getMatchingHooks 命中 (配置真实到达执行入口).
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));

        assertThat(settings.getAllHooks()).hasSize(1);

        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        Map<HookEventType, List<HookMatcher>> cfg = snapshot.getHooksConfigFromSnapshot();
        assertThat(cfg).isNotEmpty();
        assertThat(cfg.get(HookEventType.PRE_TOOL_USE)).hasSize(1);

        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        List<MatchedHook> matched = engine.getMatchingHooks(HookEvent.toolPre("Bash", null, "s1", null));
        assertThat(matched).hasSize(1);
    }

    // ── 7. 正则 matcher (防止把 matchesPattern 简化到不测正则) ─────────────

    @Test
    @DisplayName("7. 正则 matcher: 特殊字符走正则 find + legacy 别名回退")
    void regexMatcher_treatedAsRegex_withLegacyBackoff() {
        // 意图: CC matchesPattern (hooks.ts:1364-1378) — 非纯字母数字的 matcher 按正则处理,
        //       并对 legacy 别名 (如 Agent→Task) 回退 test, 保证 "^Task$" 仍能匹配 canonical "Agent".
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "^Task$", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        // matchQuery="Agent" (canonical), matcher "^Task$": 直接 test 不中, 但 getLegacyToolNames("Agent")
        // 返回 ["Task"], regex.test("Task") 命中 → true (对齐 CC legacy 回退)
        List<MatchedHook> matched = engine.getMatchingHooks(HookEvent.toolPre("Agent", null, "s1", null));
        assertThat(matched).hasSize(1);

        // 正则不匹配 → 过滤
        HooksSettings settings2 = new HooksSettings(key -> null);
        settings2.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "^Write.*", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine2 = newEngine(settings2);
        assertThat(engine2.getMatchingHooks(HookEvent.toolPre("Bash", null, "s1", null))).isEmpty();
    }

    // ── 8. if 条件匹配工具事件 (含 ruleContent 内容匹配) ───────────────────

    @Test
    @DisplayName("8. if 条件: 工具事件可求值, ruleContent 内容匹配命中/过滤")
    void ifCondition_matchesToolEvent_withContentMatch() throws Exception {
        // 意图: CC prepareIfConditionMatcher (hooks.ts:1390-1421) — if "Bash(git *)" 只对
        //       git 命令生效, 避免非匹配命令白跑 hook (process spawning overhead).
        JsonNode input = mapper.readTree("{\"command\":\"git status\"}");

        // 命中: if "Bash(git *)" + command "git status" → git 前缀匹配
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);
        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(1);

        // 过滤: if "Bash(npm *)" + command "git status" → npm 前缀不匹配
        HooksSettings settings2 = new HooksSettings(key -> null);
        settings2.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(npm *)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine2 = newEngine(settings2);
        assertThat(engine2.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).isEmpty();

        // 工具名不匹配 → 过滤: if "Edit(...)" 对 Bash 事件无效
        HooksSettings settings3 = new HooksSettings(key -> null);
        settings3.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Edit(/etc/**)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine3 = newEngine(settings3);
        assertThat(engine3.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).isEmpty();
    }

    // ── 9. 非工具事件有 if → 过滤 ──────────────────────────────────────────

    @Test
    @DisplayName("9. 非工具事件 (SessionEnd) 带 if → 过滤 (CC: ifMatcher undefined → return false)")
    void nonToolEvent_withIf_getsFiltered() {
        // 意图: CC hooks.ts:1833-1836 — 非工具事件无法求值 if, 有 if 的 hook 被过滤
        //       (ifMatcher undefined → false), 防止错误运行.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.SESSION_END,
                new CommandHook("echo end", "Bash(git *)", null, null, null, null, null, null),
                null, HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        // SessionEnd 无 reason → matchQuery null → matcher 全过, 但 if 无法求值 → 过滤
        assertThat(engine.getMatchingHooks(HookEvent.sessionEnd("s1", null, null))).isEmpty();
    }

    // ── 10. SessionStart/Setup 排除 HTTP hook ─────────────────────────────

    @Test
    @DisplayName("10. SessionStart 事件排除 HTTP hook (CC hooks.ts:1850-1864)")
    void sessionStart_excludesHttpHooks() {
        // 意图: HTTP hook 在 SessionStart/Setup 会死锁 (sandbox ask 回调未启动), CC 明确排除.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.SESSION_START,
                new HttpHook("https://example.com/hook", null, null, null, null, null, null),
                null, HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.SESSION_START,
                new CommandHook("echo start", null, null, null, null, null, null, null),
                null, HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        List<MatchedHook> matched = engine.getMatchingHooks(
            HookEvent.sessionStart("s1", null, "startup", null, null));
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).hook()).isInstanceOf(CommandHook.class);
    }

    // ── 11. 管道列表 matcher (Write|Edit) ──────────────────────────────────

    @Test
    @DisplayName("11. 管道列表 matcher (Write|Edit) 命中其中一项")
    void pipeListMatcher_matchesAnyItem() {
        // 意图: CC matchesPattern (hooks.ts:1354-1359) — "Write|Edit" 是管道精确列表, 命中任意一项.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Write|Edit", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Write", null, "s1", null))).hasSize(1);
        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", null, "s1", null))).isEmpty();
    }

    // ── 12. [对抗核验 Gap 2] 去重按类型分离 ───────────────────────────────

    @Test
    @DisplayName("12. prompt 与 agent 同 prompt+if → 两条都保留 (CC 4 个独立 Map)")
    void promptAndAgent_sameText_bothKept() {
        // 意图: CC hooks.ts:1757-1782 — uniquePromptHooks/uniqueAgentHooks 是独立 Map,
        //       同文本 prompt 与 agent hook 是<b>不同</b> hook. 旧实现单个 LinkedHashMap 用
        //       'prompt\\0if' 作 key, 同文本 prompt/agent 被错误折叠成一条 (对抗核验发现).
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new PromptHook("same text", null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new AgentHook("same text", null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        List<MatchedHook> matched = engine.getMatchingHooks(HookEvent.toolPre("Bash", null, "s1", null));
        assertThat(matched).hasSize(2);
        assertThat(matched).extracting(m -> m.hook().hookType())
            .containsExactlyInAnyOrder(HookCommand.HookType.PROMPT, HookCommand.HookType.AGENT);
    }

    @Test
    @DisplayName("13. 去重输出顺序按 command→prompt→agent→http 分组 (CC hooks.ts:1799-1806)")
    void dedup_ordersByType_groupCommandPromptAgentHttp() {
        // 意图: CC getMatchingHooks 去重后输出 = [...command, ...prompt, ...agent, ...http]
        //       (hooks.ts:1799-1806), 非原交错顺序. 本测试锁定 CC 分组顺序,
        //       避免未来改动悄悄回到交错顺序 (规则九: 验证意图).
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new HttpHook("https://x/h", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new AgentHook("agent prompt", null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new PromptHook("prompt text", null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null),
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo a", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        List<MatchedHook> matched = engine.getMatchingHooks(HookEvent.toolPre("Bash", null, "s1", null));
        assertThat(matched).hasSize(4);
        assertThat(matched).extracting(m -> m.hook().hookType())
            .containsExactly(
                HookCommand.HookType.COMMAND,
                HookCommand.HookType.PROMPT,
                HookCommand.HookType.AGENT,
                HookCommand.HookType.HTTP);
    }

    // ── 14-18. [对抗核验 Gap 3] if ruleContent 内容匹配对齐 CC preparePermissionMatcher ──

    @Test
    @DisplayName("14. Bash if 复合命令: 任一子命令命中 → hook 运行 (CC subcommands.some)")
    void bashIf_compoundCommand_anySubcommandMatches() throws Exception {
        // 意图: CC BashTool.preparePermissionMatcher (BashTool.tsx:445-468) — hook if 过滤是
        //       deny 语义, 复合命令 "ls && git push" 必须任一子命令命中 "Bash(git:*)" 安全 hook
        //       (否则复合命令绕过安全检查). 旧实现整命令 startsWith(prefix) → "ls && git push"
        //       不以 "git" 开头 → 漏过滤 (前缀不拆子命令).
        JsonNode input = mapper.readTree("{\"command\":\"ls && git push\"}");
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git:*)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(1);
    }

    @Test
    @DisplayName("15. Bash if 尾随可选: 'git *' 同时匹配裸 git (CC trailing ' *' 特例)")
    void bashIf_bareGit_matches() throws Exception {
        // 意图: CC matchWildcardPattern 对 "git *" 生成 ^git( .*)?$ — 裸 "git" 也命中
        //       (对齐前缀语义 git:*). 旧 globToRegex 用 find 语义要求 "git " + 参数, 裸 git 漏匹配.
        JsonNode input = mapper.readTree("{\"command\":\"git\"}");
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(1);
    }

    @Test
    @DisplayName("16. Bash if 前缀 'npm publish:*' + 前置 env var 剥离 → 命中")
    void bashIf_prefix_afterEnvVarStrip() throws Exception {
        // 意图: CC argv 剥离前置 VAR=val (BashTool.tsx:456-458), "NODE_ENV=prod npm publish x"
        //       必须命中 "Bash(npm publish:*)" 前缀规则 (旧实现整命令前缀匹配不剥离 env var).
        JsonNode input = mapper.readTree("{\"command\":\"NODE_ENV=prod npm publish --access public\"}");
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(npm publish:*)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(1);
    }

    @Test
    @DisplayName("17. Bash if 命令替换 → fail-safe 运行 hook (CC parse 非 simple → true)")
    void bashIf_commandSubstitution_failSafeRunsHook() throws Exception {
        // 意图: CC BashTool.tsx:452-455 — parse 非 simple (命令替换无法静态分析) → () => true,
        //       安全 hook 必须运行 (deny 语义: "无法证明子命令不匹配 → 运行"). 命令含 $() 且
        //       文本不含 "git " (旧 glob 启发式 find 必然漏) → 新实现仍命中, 旧实现过滤.
        JsonNode input = mapper.readTree("{\"command\":\"echo $(ls)\"}");
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null))).hasSize(1);
    }

    @Test
    @DisplayName("18. 无 content matcher 工具 ruleContent 非空 → 过滤 (CC patternMatcher undefined → false)")
    void noContentMatcherTool_ruleContentFilters() throws Exception {
        // 意图: CC prepareIfConditionMatcher (hooks.ts:1418-1419) — 工具无 preparePermissionMatcher
        //       时 patternMatcher undefined → ruleContent 非空 → false (过滤). 旧实现启发式取
        //       首个文本字段匹配 (更宽容): url 字段 "example.com" 恰好等于 ruleContent → 旧实现
        //       命中, CC 过滤. WebFetch 无 matcher, if "WebFetch(example.com)" 必须过滤.
        JsonNode input = mapper.readTree("{\"url\":\"example.com\"}");
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", "WebFetch(example.com)", null, null, null, null, null, null),
                "WebFetch", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        assertThat(engine.getMatchingHooks(HookEvent.toolPre("WebFetch", input, "s1", null))).isEmpty();
    }

    // ── 19. [对抗核验 Gap 4] 管道列表 matcher query 不归一化 ───────────────

    @Test
    @DisplayName("19. 管道列表 matcher: query 不归一化 (CC patterns.includes(matchQuery))")
    void pipeListMatcher_queryNotNormalized() {
        // 意图: CC matchesPattern (hooks.ts:1354-1359) — matcher.split('|').map(normalizeLegacyToolName)
        //       → patterns.includes(matchQuery), matchQuery 保持<b>原始值</b>. 旧实现把 query 也
        //       归一化 (更宽容): matcher "Agent|Write" + legacy 事件 "Task" 会被归一化命中.
        //       CC 不归一化 query → "Task" 不在 ["Agent","Write"] → 不命中 (对抗核验发现).
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo hi", null, null, null, null, null, null, null),
                "Agent|Write", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        // legacy "Task" (归一化后 → Agent) 事件: CC query 原样 "Task" 比较 → 不命中 → 过滤
        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Task", null, "s1", null))).isEmpty();
        // canonical "Agent" 事件: patterns 含 "Agent" → 命中
        assertThat(engine.getMatchingHooks(HookEvent.toolPre("Agent", null, "s1", null))).hasSize(1);
    }

    // ── 20. [H-WF2-01] safeParse 门禁 (CC hooks.ts:1405-1409) 非法 tool_input → 过滤 ──

    @Test
    @DisplayName("20. safeParse 门禁: 非法 tool_input (缺键/类型错/可选字段非法) → matcher undefined → 过滤")
    void safeParseGate_invalidToolInput_filtersHook() throws Exception {
        // 意图 (规则九 · WHY): CC inputSchema.safeParse(tool_input).success 为 false →
        //   patternMatcher undefined → ruleContent 非空即 false (hooks.ts:1405-1409, :1419).
        //   旧实现无门禁 (HookMatcherEngine.java:401 无条件调用 preparePermissionMatcher),
        //   Bash 对 command:123 等畸形输入仍制备 matcher → 可能命中并执行 CC 会过滤的 hook
        //   (WF2-X1 I-2b/d 偏移, 单向 Java 执行更多). 本测试锁定: 畸形 tool_input → 内容匹配
        //   hook 被过滤 (对齐 CC safeParse 失败 → 过滤).
        String bashConfig = "Bash(git *)";

        // 正例(控制): 合法输入 {command:"git status"} → Bash(git *) 命中 → hook 保留
        JsonNode valid = mapper.readTree("{\"command\":\"git status\"}");
        assertThat(engineWithIf("Bash", bashConfig)
            .getMatchingHooks(HookEvent.toolPre("Bash", valid, "s1", null))).hasSize(1);

        // I-2a 必填键缺失: {} 缺 command → missing_required → safeParse 失败 → 过滤
        JsonNode missingRequired = mapper.readTree("{}");
        assertThat(engineWithIf("Bash", bashConfig)
            .getMatchingHooks(HookEvent.toolPre("Bash", missingRequired, "s1", null))).isEmpty();

        // I-2b 必填键类型错: {command:123} 数字 → invalid_type → 过滤 (CC z.string 不 coerce).
        //   <b>判别用例</b>: 用 if "Bash(123 *)" — 旧实现 asText coerce 后 command="123" 命中
        //   该模式 → hook 保留 (RED); 新门禁 safeParse 拒绝数字 command → 过滤 (GREEN).
        JsonNode wrongType = mapper.readTree("{\"command\":123}");
        assertThat(engineWithIf("Bash", "Bash(123 *)")
            .getMatchingHooks(HookEvent.toolPre("Bash", wrongType, "s1", null))).isEmpty();

        // I-2d 可选字段非法: {command:"git status", timeout:"abc"} → timeout 声明 integer,
        //   "abc" 类型错 → safeParse 失败 → 过滤 (可选字段非法同样使整个 safeParse 失败)
        JsonNode badOptional = mapper.readTree("{\"command\":\"git status\",\"timeout\":\"abc\"}");
        assertThat(engineWithIf("Bash", bashConfig)
            .getMatchingHooks(HookEvent.toolPre("Bash", badOptional, "s1", null))).isEmpty();
    }

    @Test
    @DisplayName("21. safeParse 门禁: strictObject 未知键 (schema additionalProperties:false) → 过滤")
    void safeParseGate_unknownKeys_forStrictTool_filtersHook() throws Exception {
        // 意图 (规则九 · WHY): CC Bash/Read/Glob 均 z.strictObject (BashTool.tsx:227 /
        //   FileReadTool.ts:228 / GlobTool.ts:27) → 未知键拒绝 → safeParse 失败 → matcher
        //   undefined → 过滤 (WF2-X1 I-2c). Java Read schema 声明 additionalProperties:false
        //   (ReadFileTool.java:338) → 未知键被 ToolInputValidator 逐键拒绝 (unrecognized_keys).
        //   注: Bash/Glob Java schema 未广告 additionalProperties:false → I-2c 残留登记为工具层项
        //   (H-WF2 patch-note), 不在本任务范围.
        String readConfig = "Read(/a/**)";
        ReadFileTool readTool = new ReadFileTool(new PathGuard(Paths.get(".")));

        // 正例(控制): 合法 Read 输入 {file_path:"/a/b"} → Read(/a/**) 通配命中 → hook 保留
        JsonNode validRead = mapper.readTree("{\"file_path\":\"/a/b\"}");
        assertThat(engineWithTools("Read", readConfig, readTool)
            .getMatchingHooks(HookEvent.toolPre("Read", validRead, "s1", null))).hasSize(1);

        // 未知键: {file_path:"/a/b", junk:1} → unrecognized_keys → safeParse 失败 → 过滤
        JsonNode unknownKey = mapper.readTree("{\"file_path\":\"/a/b\",\"junk\":1}");
        assertThat(engineWithTools("Read", readConfig, readTool)
            .getMatchingHooks(HookEvent.toolPre("Read", unknownKey, "s1", null))).isEmpty();
    }

    // ── [MT-02] registered/插件源并入统一单链 ──────────────────────────────

    @Test
    @DisplayName("22. registered 源并入统一链: matcher 过滤 + hookSource 三元 (plugin:name) + 去重")
    void registeredMatchers_mergeIntoUnifiedChain_withPluginContext() {
        // WHY (OPD-WF2-MT-02): CC getHooksConfig 把 registered 源（PluginHookMatcher）并入
        //   getMatchingHooks 统一单链 (hooks.ts:1519-1529), 随后统一 matcher 过滤/去重/if
        //   (hooks.ts:1681-1848). 本测试验证: 注册 plugin matcher (matcher="Read" + pluginRoot/
        //   pluginName) → getMatchingHooks 返回集包含该插件 hook, 且带 plugin context +
        //   hookSource="plugin:name"; matcher 不匹配时过滤.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo settings", null, null, null, null, null, null, null),
                "Read", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        List<HookMatcherEngine.RegisteredHookMatcher> registered = List.of(
            new HookMatcherEngine.RegisteredHookMatcher(
                "Read", "/root/plug", "demo", "demo", null,
                List.of(new CommandHook("echo plugin", null, null, null, null, null, null, null)))
        );

        // matcher 匹配 (Read 事件): settings + registered 插件 hook 都在返回集
        List<MatchedHook> matched = engine.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null), List.of(), registered);
        assertThat(matched).hasSize(2);
        MatchedHook plugin = matched.stream()
            .filter(m -> m.pluginRoot() != null).findFirst().orElseThrow();
        assertThat(plugin.hookSource()).isEqualTo("plugin:demo");
        assertThat(plugin.pluginId()).isEqualTo("demo");
        assertThat(plugin.pluginRoot()).isEqualTo("/root/plug");
        MatchedHook settingsHook = matched.stream()
            .filter(m -> m.pluginRoot() == null).findFirst().orElseThrow();
        assertThat(settingsHook.hookSource()).isEqualTo("settings");

        // matcher 不匹配 (Bash 事件): registered 插件 hook 被过滤, settings 也过滤 (同源语义)
        assertThat(engine.getMatchingHooks(
            HookEvent.toolPre("Bash", null, "s1", null), List.of(), registered)).isEmpty();
    }

    @Test
    @DisplayName("23. registered 源去重: 同命令 settings+plugin 折叠, pluginRoot 命名空间保留跨插件")
    void registeredMatchers_dedup_withPluginNamespace() {
        // WHY (MT-02): CC 全集合去重 hookDedupKey (hooks.ts:1453-1455) 以 pluginRoot/skillRoot
        //   为前缀 —— 同命令的 settings hook 与 plugin hook 不折叠 (不同命名空间), 两个插件
        //   同名 hook 亦不折叠; 仅同源同命令折叠.
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo dup", null, null, null, null, null, null, null),
                "Read", HookSource.USER_SETTINGS, null)
        ));
        HookMatcherEngine engine = newEngine(settings);

        List<HookMatcherEngine.RegisteredHookMatcher> registered = List.of(
            new HookMatcherEngine.RegisteredHookMatcher(
                "Read", "/root/plugA", "demoA", "demoA", null,
                List.of(new CommandHook("echo dup", null, null, null, null, null, null, null))),
            new HookMatcherEngine.RegisteredHookMatcher(
                "Read", "/root/plugB", "demoB", "demoB", null,
                List.of(new CommandHook("echo dup", null, null, null, null, null, null, null)))
        );

        // settings + 两个插件同名命令 → 3 条 (settings 前缀 '' 与 plugA/plugB 前缀不同)
        List<MatchedHook> matched = engine.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null), List.of(), registered);
        assertThat(matched).hasSize(3);
    }

    // ── [H-WF2-01] safeParse 门禁测试专用构造 ──────────────────────────────

    /** 单 matcher + if 条件的引擎 (供 safeParse 门禁用例). */
    private HookMatcherEngine engineWithIf(String matcher, String ifCondition) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo probe", ifCondition, null, null, null, null, null, null),
                matcher, HookSource.USER_SETTINGS, null)
        ));
        return newEngine(settings);
    }

    /** 指定 matcher + if + 指定工具注册表 (供 strictObject 未知键用例). */
    private HookMatcherEngine engineWithTools(String matcher, String ifCondition, Tool tool) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo probe", ifCondition, null, null, null, null, null, null),
                matcher, HookSource.USER_SETTINGS, null)
        ));
        return newEngineWithTools(settings, tool);
    }
}
