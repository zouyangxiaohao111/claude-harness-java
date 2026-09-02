package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.SessionHookStore;

import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookMatcher;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.PromptHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P1-4] FrontmatterHooks 27 事件 + HookCommand 4 类型对齐测试.
 *
 * <p>旧 HooksSettings 仅 5 具名字段 (preToolUse/postToolUse/stop/subagentStop/sessionStart) 丢弃
 * 22 事件; HookMatcher.hooks 是 List&lt;String&gt; 对 CC hook 对象 toString() 得 [object Object];
 * timeout 在 matcher 级. 现改为 Map&lt;HookEventType, List&lt;permission.hook.HookMatcher&gt;&gt;
 * (27 事件全支持, 对齐 CC hooks.ts:211-213 HooksSchema partialRecord), hooks 是
 * List&lt;HookCommand&gt; (4 类型), timeout 在 HookCommand 级.
 */
class FrontmatterHooksAlignmentTest {

    @Test
    @DisplayName("fromMap 支持 27 事件白名单中的冷门事件 (PreToolUse/FileChanged)")
    void hooksSettings_supports_all_27_hookEventTypes() {
        // WHY: 旧 HooksSettings 仅 5 具名字段, 22 种事件 hooks 静默丢弃. Map<HookEventType, List<HookMatcher>>
        // 后任意 CC 事件名 (coreTypes.ts:25-52 HOOK_EVENTS) 均可映射.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PreToolUse", List.of(Map.of("matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo a")))));
        raw.put("FileChanged", List.of(Map.of("matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo b")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).containsKey(HookEventType.PRE_TOOL_USE);
        assertThat(result).containsKey(HookEventType.FILE_CHANGED);
    }

    @Test
    @DisplayName("HookMatcher.hooks 是 List<HookCommand>, 取 CommandHook.command() 可执行")
    void hookMatcher_hooks_is_list_of_HookCommand_not_string() {
        // WHY: 旧 hooks 是 List<String>, 对 CC 格式 hook 对象调 toString() 得 [object Object],
        // hook 命令永远无法执行. 现取 CommandHook.command() 字段.
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo hi")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);
        HookMatcher m = result.get(HookEventType.PRE_TOOL_USE).get(0);
        assertThat(m.hooks().get(0)).isInstanceOf(HookCommand.class);
        CommandHook cmd = (CommandHook) m.hooks().get(0);
        assertThat(cmd.command()).isEqualTo("echo hi");
    }

    @Test
    @DisplayName("timeout 在 HookCommand 级 (对齐 CC hooks.ts:42), HookMatcher 无 timeout 字段")
    void timeout_is_on_HookCommand_not_HookMatcher() {
        // WHY: CC hooks.ts:42/75/101/144 timeout 全在 HookCommand 级, HookMatcherSchema (hooks.ts:194-204)
        // 仅 matcher + hooks 两字段. 旧 Java matcher 级 timeoutSeconds 字段错位.
        // [IMP-SUB-10 返工] timeout 用数值 42 (CC z.number().positive(), 字符串 "42" zod 会拒绝 → 整块丢弃).
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo hi", "timeout", 42)))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);
        CommandHook cmd = (CommandHook) result.get(HookEventType.PRE_TOOL_USE).get(0).hooks().get(0);
        assertThat(cmd.timeout()).isEqualTo(42);

        // HookMatcher record 仅 matcher + hooks 两个字段 (无 timeoutSeconds)
        assertThat(HookMatcher.class.getRecordComponents())
            .extracting(c -> c.getName())
            .containsExactlyInAnyOrder("matcher", "hooks");
    }

    @Test
    @DisplayName("HookCommand 判别 record 支持 4 类型 command/prompt/agent/http (对齐 CC hooks.ts:176-189)")
    void hookCommand_supports_4_types_command_prompt_agent_http() {
        // WHY: CC HookCommandSchema = discriminatedUnion('type', [Command, Prompt, Agent, Http]).
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(
                Map.of("type", "command", "command", "echo hi"),
                Map.of("type", "prompt", "prompt", "Are you sure?"),
                Map.of("type", "agent", "prompt", "Please help me"),
                Map.of("type", "http", "url", "https://example.com")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);
        List<HookCommand> hooks = result.get(HookEventType.PRE_TOOL_USE).get(0).hooks();
        assertThat(hooks).hasSize(4);
        assertThat(hooks.get(0)).isInstanceOf(CommandHook.class);
        assertThat(hooks.get(1)).isInstanceOf(PromptHook.class);
        assertThat(hooks.get(2)).isInstanceOf(AgentHook.class);
        assertThat(hooks.get(3)).isInstanceOf(HttpHook.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S8 H7/CCJ-HOOKS-T8-03] matcher 缺省 '' + 4 类型全注册
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-03] matcher 字段缺失时 CC 存 '' (registerFrontmatterHooks.ts:48
     * {@code matcherConfig.matcher ?? ''}); 旧 Java parseMatcher 缺省 '*' 泄漏到
     * SessionHookStore.getSessionHooks 持久化/视图. 匹配语义等价 (匹配一切), 存储表示必须 ''.
     */
    @Test
    @DisplayName("matcher 字段缺失 → 缺省 '' (对齐 CC registerFrontmatterHooks.ts:48 ?? '')")
    void parseMatcher_missingMatcher_defaultsToEmptyString() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PreToolUse", List.of(Map.of(
            "hooks", List.of(Map.of("type", "command", "command", "echo a")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result.get(HookEventType.PRE_TOOL_USE).get(0).matcher())
            .as("matcher 缺省必须 '' (CC ?? ''), 非 '*'")
            .isEqualTo("");
    }

    /**
     * WHY: [IMP-HOOKS-S8 H7 CCJ-HOOKS-T8-01] CC registerFrontmatterHooks.ts:55-58 对 hooks 数组内
     * 每个 hook (command/prompt/http/agent 4 类型) 无条件 addSessionHook — 无类型过滤. 旧 Java
     * registerMatchers 仅 COMMAND 注册, PROMPT/AGENT/HTTP log.warn 跳过 → agent frontmatter 的
     * prompt/http/agent 钩子全部静默缺失 (注册面). 执行链分发 (HookRegistry.executeOneConfiguredHook)
     * 已具备 4 分支, 本测试只断言注册面.
     */
    @Test
    @DisplayName("register 注册 4 类型 hook (command/prompt/http/agent 无过滤, 对齐 CC :55-58)")
    void register_registersAllFourHookTypes() {
        HookRegistry hookRegistry = new HookRegistry();
        List<HookCommand> commands = List.of(
            new CommandHook("echo a", null, null, null, null, false, false, false),
            new PromptHook("Are you sure?", null, null, null, null, false),
            new HttpHook("https://example.com/hook", null, null, null, null, null, false),
            new AgentHook("Please help me", null, null, null, null, false));
        Map<HookEventType, List<HookMatcher>> settings = Map.of(
            HookEventType.PRE_TOOL_USE, List.of(new HookMatcher("*", commands)));

        int count = FrontmatterHooks.register(hookRegistry, "agent-1", "subagent", settings);

        assertThat(count).as("4 类型 hook 必须全部注册 (CC 无类型过滤)").isEqualTo(4);
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> hooks =
                hookRegistry.getSessionHooks("agent-1", HookEventType.PRE_TOOL_USE);
        assertThat(hooks).containsKey(HookEventType.PRE_TOOL_USE);
        assertThat(hooks.get(HookEventType.PRE_TOOL_USE).get(0).hooks())
            .as("SessionHookStore 必须可见全部 4 个 hook (prompt/http/agent 不得被丢弃)")
            .hasSize(4);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-SUB-10 D15] HooksSchema 整体校验对齐 CC（坏事件丢全部）
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC parseHooksFromFrontmatter（loadAgentsDir.ts:432-436）用 HooksSchema().safeParse 做
     * 整块校验，任一事件名不在 HOOK_EVENTS 27 事件白名单 → safeParse 失败 → 返回 undefined →
     * agent 无任何 hooks。旧 Java fromMap 逐事件容错（未知事件跳过、合法事件保留）→ FH-37/39 漂移。
     */
    @Test
    @DisplayName("D15 未知 hook 事件名 → 整块丢弃 (对齐 CC safeParse 失败→undefined)")
    void fromMap_unknownEvent_dropsAllHooks() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PreToolUse", List.of(Map.of("matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo a")))));
        raw.put("NotARealEvent", List.of(Map.of("matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo b")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("任一事件非法 → 整块 hooks 丢弃 (CC safeParse 失败→undefined)")
            .isEmpty();
    }

    /**
     * WHY: CC HooksSchema value 是 z.array(HookMatcherSchema)（hooks.ts:211-213），事件值非数组 →
     * safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("D15 事件值非数组 → 整块丢弃 (CC z.array(HookMatcherSchema))")
    void fromMap_eventValueNotList_dropsAllHooks() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PreToolUse", List.of(Map.of("matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo a")))));
        raw.put("Stop", "not-a-list");

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).isEmpty();
    }

    /**
     * WHY: CC HookMatcherSchema hooks 是 z.array(HookCommandSchema) 必填（hooks.ts:200-202），
     * matcher 缺 hooks 或非数组 → safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("D15 matcher 缺 hooks / hooks 非数组 → 整块丢弃 (CC hooks: z.array 必填)")
    void fromMap_matcherMissingHooks_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(
            Map.of("matcher", "*")));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("matcher 缺 hooks 必填数组 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: CC HookMatcherSchema matcher: z.string().optional()（hooks.ts:196-199）只接受缺省/undefined,
     * 拒绝 JSON null —— YAML 空值 `matcher:` 解析为 null 时 zod safeParse 失败 → 整块 hooks 丢弃.
     * 旧 Java validateMatcherStrict 仅拒绝"非 null 非 String", null 放行注册 (matcher '') → 接受
     * CC 会整块拒绝的配置 (D15 目标行为类内漂移). 修复后 matcher 键存在即须 String.
     */
    @Test
    @DisplayName("返工2 matcher:null → 整块丢弃 (CC z.string().optional() 拒绝 JSON null)")
    void fromMap_matcherNull_dropsAllHooks() {
        // WHY: Map.of 拒绝 null 值, 需 LinkedHashMap 承载 matcher:null（YAML 空值解析形态）
        Map<String, Object> matcherMap = new LinkedHashMap<>();
        matcherMap.put("matcher", null);
        matcherMap.put("hooks", List.of(Map.of("type", "command", "command", "echo a")));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("PreToolUse", List.of(matcherMap));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("matcher null → zod 拒绝 → 整块丢弃 (含 hooks 亦不注册)")
            .isEmpty();
    }

    /**
     * WHY: CC HookCommandSchema discriminatedUnion('type')（hooks.ts:176-189），command 类型
     * 缺 command 必填字段（BashCommandHookSchema command: z.string(), hooks.ts:34）→ safeParse 失败
     * → 整块丢弃。旧 Java 跳过该 command、保留其余合法事件 → FH-39 漂移。
     */
    @Test
    @DisplayName("D15 command hook 缺 command 必填字段 → 整块丢弃 (CC hooks.ts:34)")
    void fromMap_commandMissingRequiredField_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "command")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("任一 command 非法 → 整块 hooks 丢弃").isEmpty();
    }

    /**
     * WHY: CC discriminatedUnion('type') 只认 command/prompt/http/agent 4 类型，未知 type →
     * safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("D15 未知 hook type → 整块丢弃 (CC discriminatedUnion('type'))")
    void fromMap_unknownCommandType_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "teleport", "command", "echo a")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-SUB-10 返工] 字段类型级严格（CC zod 全字段类型校验 → 任一非法整块丢弃）
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC BashCommandHookSchema timeout: z.number().positive().optional()（hooks.ts:42-46）——
     * 字段存在但类型/值域非法（字符串 "abc"、非正数 0）→ HooksSchema().safeParse 失败 → 整块丢弃。
     * 旧 Java asInt 静默容错成 null 并注册该 hook → 接受 CC 会整块拒绝的配置。
     */
    @Test
    @DisplayName("返工 timeout 非数/非正数 → 整块丢弃 (CC z.number().positive())")
    void fromMap_timeoutNotPositiveNumber_dropsAllHooks() {
        Map<HookEventType, List<HookMatcher>> stringTimeout = FrontmatterHooks.fromMap(Map.of(
            "PreToolUse", List.of(Map.of("matcher", "*",
                "hooks", List.of(Map.of("type", "command", "command", "echo hi", "timeout", "abc"))))));
        assertThat(stringTimeout)
            .as("timeout 字符串 → zod 拒绝 → 整块丢弃")
            .isEmpty();

        Map<HookEventType, List<HookMatcher>> zeroTimeout = FrontmatterHooks.fromMap(Map.of(
            "PreToolUse", List.of(Map.of("matcher", "*",
                "hooks", List.of(Map.of("type", "command", "command", "echo hi", "timeout", 0))))));
        assertThat(zeroTimeout)
            .as("timeout 0 非正数 → zod 拒绝 → 整块丢弃")
            .isEmpty();
    }

    /**
     * WHY: CC HttpHookSchema url: z.string().url()（hooks.ts:99）——非合法 URL → safeParse 失败 → 整块丢弃。
     * 旧 Java 仅校验 String（不校验格式）并注册该 http hook → 接受 CC 会整块拒绝的配置。
     */
    @Test
    @DisplayName("返工 http url 非合法 URL → 整块丢弃 (CC z.string().url())")
    void fromMap_httpUrlNotUrl_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "http", "url", "not-a-url")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("http url 非 URL → zod 拒绝 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: CC once: z.boolean().optional()（hooks.ts:51-54）——非布尔（字符串 "yes"）→ safeParse 失败
     * → 整块丢弃。旧 Java asBool 静默解析成 false 并注册 → 接受 CC 会整块拒绝的配置。
     */
    @Test
    @DisplayName("返工 once 非布尔 → 整块丢弃 (CC z.boolean())")
    void fromMap_onceNotBoolean_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo hi", "once", "yes")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("once 字符串 → zod 拒绝 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: CC shell: z.enum(SHELL_TYPES).optional()（hooks.ts:36-41, SHELL_TYPES=['bash','powershell']）
     * ——shell 非枚举值（"cmd"）→ safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("返工 shell 非枚举 → 整块丢弃 (CC z.enum(SHELL_TYPES))")
    void fromMap_shellNotInEnum_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "command", "command", "echo hi", "shell", "cmd")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("shell='cmd' ∉ {bash,powershell} → zod 拒绝 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: CC headers: z.record(z.string(), z.string()).optional()（hooks.ts:106-111）——非记录（字符串）
     * → safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("返工 http headers 非 string→string 记录 → 整块丢弃 (CC z.record)")
    void fromMap_headersNotStringRecord_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "http", "url", "https://example.com", "headers", "foo")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("headers 字符串 → zod 拒绝 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: CC allowedEnvVars: z.array(z.string()).optional()（hooks.ts:112-117）——非 String 数组
     * → safeParse 失败 → 整块丢弃。
     */
    @Test
    @DisplayName("返工 http allowedEnvVars 非 String 数组 → 整块丢弃 (CC z.array(z.string()))")
    void fromMap_allowedEnvVarsNotStringArray_dropsAllHooks() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(Map.of("type", "http", "url", "https://example.com",
                "allowedEnvVars", "MY_VAR")))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).as("allowedEnvVars 字符串 → zod 拒绝 → 整块丢弃").isEmpty();
    }

    /**
     * WHY: 正向控制——合法字段类型（timeout 正数、once/async 布尔、shell 枚举、if String、
     * headers string→string 记录、allowedEnvVars String 数组）仍被接受（CC zod 全字段合法 →
     * safeParse 成功 → 正常解析）。防止严格校验过度拒绝合法配置。
     */
    @Test
    @DisplayName("返工 合法字段类型仍解析成功 (CC zod 全字段合法)")
    void fromMap_validFieldTypes_parseOk() {
        Map<String, Object> raw = Map.of("PreToolUse", List.of(Map.of(
            "matcher", "*",
            "hooks", List.of(
                Map.of("type", "command", "command", "echo hi",
                    "timeout", 30, "once", true, "async", false, "shell", "bash", "if", "Bash(git *)"),
                Map.of("type", "http", "url", "https://example.com",
                    "timeout", 10, "once", false,
                    "headers", Map.of("Authorization", "Bearer $TOKEN"),
                    "allowedEnvVars", List.of("TOKEN"))))));

        Map<HookEventType, List<HookMatcher>> result = FrontmatterHooks.fromMap(raw);

        assertThat(result).containsKey(HookEventType.PRE_TOOL_USE);
        List<HookCommand> hooks = result.get(HookEventType.PRE_TOOL_USE).get(0).hooks();
        assertThat(hooks).hasSize(2);
        CommandHook cmd = (CommandHook) hooks.get(0);
        assertThat(cmd.timeout()).isEqualTo(30);
        assertThat(cmd.once()).isTrue();
        assertThat(cmd.shell()).isEqualTo("bash");
        HttpHook http = (HttpHook) hooks.get(1);
        assertThat(http.timeout()).isEqualTo(10);
        assertThat(http.headers()).containsEntry("Authorization", "Bearer $TOKEN");
        assertThat(http.allowedEnvVars()).containsExactly("TOKEN");
    }
}
