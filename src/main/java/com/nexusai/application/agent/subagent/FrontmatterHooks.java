package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookMatcher;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.PromptHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Frontmatter Hooks 注册 · 对齐 CC utils/hooks/registerFrontmatterHooks.ts:557-575 +
 * schemas/hooks.ts:194-220 (HookMatcherSchema / HooksSchema).
 *
 * <p>agent frontmatter 可以定义 session-scoped hooks，agent 启动时注册。
 * 关键行为：isAgent=true 时，Stop 事件会自动转换为 SubagentStop 事件。
 *
 * <p><b>[Session S1 P1-4 对齐]</b>: 旧实现内嵌 {@code HooksSettings} (5 具名字段) +
 * {@code HookMatcher} (matcher/hooks:List&lt;String&gt;/timeoutSeconds) 与 CC schema 错位:
 * <ul>
 *   <li>CC {@code HooksSchema} (hooks.ts:211-213) = {@code partialRecord(enum(HOOK_EVENTS 27 事件), array(HookMatcherSchema))}
 *       -- 27 事件 Map, 非 5 具名字段. 旧实现丢弃 22 事件.</li>
 *   <li>CC {@code HookMatcherSchema} (hooks.ts:194-204) = {@code {matcher?: string, hooks: HookCommand[]}}
 *       -- hooks 是 HookCommand 列表 (判别联合 4 类型), 非 String 列表; 无 matcher 级 timeout.</li>
 *   <li>CC {@code timeout} (hooks.ts:42/75/101/144) 全在 HookCommand 级, 非 matcher 级.</li>
 *   <li>旧实现 {@code h.toString()} 对 CC hook 对象得 {@code [object Object]}, hook 命令永不执行.</li>
 * </ul>
 * 本期复用 {@code permission.hook} 包已 CC 对齐的 {@link HookMatcher} / {@link HookCommand} /
 * {@link CommandHook} / {@link PromptHook} / {@link HttpHook} / {@link AgentHook} (DRY, 规则#7 不调和冲突),
 * 删除内嵌脏 record. HooksSettings 改为 {@code Map<HookEventType, List<HookMatcher>>} (27 事件全支持).
 *
 * <p><b>本期范围</b> [IMP-HOOKS-S8 H7]: frontmatter hook 的 4 类型
 * {@code command}/{@code prompt}/{@code agent}/{@code http} 全部注册进 SessionHookStore
 * （对齐 CC registerFrontmatterHooks.ts:55-58 无类型过滤），执行走标准 session hook 链
 * HookRegistry.executeOneConfiguredHook 的 4 分支分发（prompt/agent/http executor 字段面
 * 完整对齐属 T4 域）；command 类型保留 command 字段必填 fail-loud 守卫（镜像 CC
 * BashCommandHookSchema {@code command: z.string()} 必填, schemas/hooks.ts:34）。
 *
 * <p><b>[IMP-SUB-10 D15] zod 整体校验对齐</b>（FH-37/39/40）：CC {@code parseHooksFromFrontmatter}
 * （loadAgentsDir.ts:424-441）用 {@code HooksSchema().safeParse}（schemas/hooks.ts:211-213）
 * 做整块校验，任一事件/命令非法 → 整块 hooks 丢弃（返回 {@code undefined}）。本类
 * {@link #fromMap} 从"逐事件容错"改为"坏事件丢全部"（任一非法 → 空 Map），
 * 见 {@link #validateHooksSchemaStrict}。
 */
public class FrontmatterHooks {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterHooks.class);

    /** CC HookCommandSchema discriminatedUnion type（schemas/hooks.ts:176-189） */
    private static final java.util.Set<String> HOOK_COMMAND_TYPES =
        java.util.Set.of("command", "prompt", "agent", "http");

    /** CC SHELL_TYPES（utils/shell/shellProvider.ts:1）= ['bash', 'powershell'] */
    private static final java.util.Set<String> SHELL_TYPES =
        java.util.Set.of("bash", "powershell");


    // ════════════════════════════════════════════════════════════════════════
    // 解析 · 对齐 CC HooksSchema (hooks.ts:211-213) partialRecord(27 事件, array(HookMatcherSchema))
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从 frontmatter raw Map 解析为 {@code Map<HookEventType, List<HookMatcher>>}.
     *
     * <p>对齐 CC {@code parseHooksFromFrontmatter}（loadAgentsDir.ts:424-441）+ {@code HooksSchema}
     * （schemas/hooks.ts:211-213）{@code partialRecord(z.enum(HOOK_EVENTS), array(HookMatcherSchema))}。
     *
     * <p><b>[IMP-SUB-10 D15] zod 整体校验对齐（坏事件丢全部）</b>：CC 用
     * {@code HooksSchema().safeParse(frontmatter.hooks)}（loadAgentsDir.ts:432）做<b>整块</b>校验——
     * 任一事件名非法、任一事件值非数组、任一 matcher/command 结构非法 → {@code safeParse} 失败
     * → {@code parseHooksFromFrontmatter} 返回 {@code undefined} → agent 定义<b>不携带任何 hooks</b>。
     * 旧 Java {@code fromMap} 是<b>逐事件容错</b>（未知事件跳过、非法 matcher 跳过、非法 command 跳过，
     * 保留合法事件）——FH-37/39/40 语义漂移。本方法改为严格整体校验：任一非法 → 返回空 Map
     * （注册 0 个，等价 CC agent 无 hooks），并 log.warn 输出 zod 错误描述。
     *
     * <p>[IMP-SUB-10 返工] 校验级别提升至 CC zod <b>全字段类型级</b>：除结构 + 必填键外，
     * 字段类型/值域非法同样整块丢弃（等价 CC {@code HooksSchema().safeParse} 失败）——timeout 非正数、
     * once/async/asyncRewake 非布尔、shell ∉ {bash,powershell}、http url 非合法 URL、
     * if/model/statusMessage 非 String、headers 非 string→string 记录、allowedEnvVars 非 String 数组。
     * 与 loadAgentsDir.isValidHooks 同步提升（两校验点一致决策，返工项 #3）。
     *
     * @param map frontmatter hooks 字段 raw Map (可为 null)
     * @return 事件 -> 匹配器列表 (不可变语义, 空入参返回空 Map; 任一事件非法 → 空 Map 整块丢弃)
     */
    public static Map<HookEventType, List<HookMatcher>> fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return new LinkedHashMap<>();

        // [IMP-SUB-10 D15] zod HooksSchema 整体校验：任一非法 → 整块丢弃（CC safeParse 失败→undefined）
        String invalid = validateHooksSchemaStrict(map);
        if (invalid != null) {
            log.warn("[FrontmatterHooks] HooksSchema 校验失败, 整块 hooks 丢弃 "
                    + "(CC parseHooksFromFrontmatter safeParse 失败 → undefined): {}", invalid);
            return new LinkedHashMap<>();
        }

        Map<HookEventType, List<HookMatcher>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            // 已过严格校验：type 必非 null、value 必为 List、matcher/command 必合法
            HookEventType type = HookEventType.fromCcName(entry.getKey());
            List<?> rawList = (List<?>) entry.getValue();
            List<HookMatcher> matchers = new ArrayList<>();
            for (Object item : rawList) {
                HookMatcher matcher = parseMatcher(asStringMap((Map<?, ?>) item));
                if (matcher != null && !matcher.hooks().isEmpty()) {
                    matchers.add(matcher);
                }
            }
            if (!matchers.isEmpty()) {
                result.put(type, matchers);
            }
        }
        return result;
    }

    /**
     * zod HooksSchema 严格整体校验 · CC original: {@code HooksSchema().safeParse(frontmatter.hooks)}
     * （loadAgentsDir.ts:432-436；schemas/hooks.ts:211-213）。
     *
     * <p>任一事件/命令非法 → 返回错误描述（整块丢弃）；全部合法 → {@code null}。
     * [IMP-SUB-10 返工] 全字段类型级（结构 + 必填键 + 字段类型/值域，见 {@link #validateCommandStrict}）；
     * 与 loadAgentsDir.isValidHooks 同步（两校验点一致决策）。
     */
    private static String validateHooksSchemaStrict(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String eventName = entry.getKey();
            if (HookEventType.fromCcName(eventName) == null) {
                return "未知 hook 事件名 '" + eventName + "' (CC HOOK_EVENTS 27 事件白名单外)";
            }
            if (!(entry.getValue() instanceof List<?> rawList)) {
                return "事件 '" + eventName + "' 值非数组 (CC z.array(HookMatcherSchema))";
            }
            for (Object matcher : rawList) {
                String matcherErr = validateMatcherStrict(matcher);
                if (matcherErr != null) {
                    return "事件 '" + eventName + "' matcher 非法: " + matcherErr;
                }
            }
        }
        return null;
    }

    /** HookMatcherSchema 严格校验 · CC original（schemas/hooks.ts:194-204）。 */
    private static String validateMatcherStrict(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return "matcher 非对象 (CC HookMatcherSchema z.object)";
        }
        // [IMP-SUB-10 返工2] zod z.string().optional()（hooks.ts:196-199）只接受缺省/undefined,
        // 拒绝 JSON null —— matcher 键存在即须 String (null 亦拒绝). YAML 空值 `matcher:`
        // 解析为 null 时, Java 注册 hooks 而 CC safeParse 失败 → 整块丢弃 (D15 语义漂移修复).
        if (m.containsKey("matcher") && !(m.get("matcher") instanceof String)) {
            return "matcher 字段必须为 String, null 亦拒绝 (CC matcher: z.string().optional(), hooks.ts:196-199)";
        }
        Object hooks = m.get("hooks");
        if (!(hooks instanceof List<?> hookList)) {
            return "hooks 字段缺失或非数组 (CC hooks: z.array(HookCommandSchema) 必填)";
        }
        for (Object command : hookList) {
            String cmdErr = validateCommandStrict(command);
            if (cmdErr != null) {
                return "hook command 非法: " + cmdErr;
            }
        }
        return null;
    }

    /**
     * HookCommandSchema 严格校验 · CC original: discriminatedUnion('type')（schemas/hooks.ts:176-189）。
     *
     * <p>[IMP-SUB-10 返工] 提升至 zod <b>全字段类型级</b>：除 type 白名单 + 必填键外，
     * 可选字段存在即须类型/值域合法（等价 CC {@code HooksSchema().safeParse} 失败 → 整块丢弃）：
     * <ul>
     *   <li>{@code if} String（IfConditionSchema, hooks.ts:19-27）</li>
     *   <li>{@code timeout} 正数（z.number().positive(), hooks.ts:42/75/101/144）</li>
     *   <li>{@code once}/{@code async}/{@code asyncRewake} Boolean（z.boolean(), hooks.ts:51/55/59 等）</li>
     *   <li>{@code shell} ∈ {bash,powershell}（z.enum(SHELL_TYPES), hooks.ts:36-41）</li>
     *   <li>{@code model}/{@code statusMessage} String（z.string(), hooks.ts:81/149/47 等）</li>
     *   <li>http {@code url} 合法 URL（z.string().url(), hooks.ts:99）</li>
     *   <li>http {@code headers} string→string 记录（z.record, hooks.ts:106-111）</li>
     *   <li>http {@code allowedEnvVars} String 数组（z.array(z.string()), hooks.ts:112-117）</li>
     * </ul>
     * 键存在但值为 null 同样非法（zod .optional() 只接受缺省/undefined，拒绝 JSON null）。
     * 包内共享：loadAgentsDir.isValidHookCommand 委托本方法（两校验点一致决策）。
     */
    static String validateCommandStrict(Object v) {
        if (!(v instanceof Map<?, ?> h)) {
            return "command 非对象 (CC discriminatedUnion('type'))";
        }
        Object type = h.get("type");
        if (!(type instanceof String ts) || !HOOK_COMMAND_TYPES.contains(ts)) {
            return "type 非 command/prompt/http/agent (CC discriminatedUnion('type'))";
        }
        switch (ts) {
            case "command":
                // CC BashCommandHookSchema (hooks.ts:32-65)
                if (!(h.get("command") instanceof String)) {
                    return "command 字段缺失或非字符串 (CC command: z.string() 必填, hooks.ts:34)";
                }
                return validateCommandOptionalFields(h, "command");
            case "prompt":
                // CC PromptHookSchema (hooks.ts:67-95)
                if (!(h.get("prompt") instanceof String)) {
                    return "prompt 字段缺失或非字符串 (CC prompt: z.string() 必填, hooks.ts:69)";
                }
                return validateCommandOptionalFields(h, "prompt");
            case "http":
                // CC HttpHookSchema (hooks.ts:97-126)
                if (!(h.get("url") instanceof String url) || !isValidUrl(url)) {
                    return "url 缺失/非字符串/非合法 URL (CC url: z.string().url() 必填, hooks.ts:99)";
                }
                return validateCommandOptionalFields(h, "http");
            case "agent":
                // CC AgentHookSchema (hooks.ts:128-163)
                if (!(h.get("prompt") instanceof String)) {
                    return "prompt 字段缺失或非字符串 (CC prompt: z.string() 必填, hooks.ts:138)";
                }
                return validateCommandOptionalFields(h, "agent");
            default:
                return "未知 type '" + ts + "'";
        }
    }

    /** 四 schema 共有可选字段（if/timeout/statusMessage/once）+ 各类型独有可选字段的类型级校验。 */
    private static String validateCommandOptionalFields(Map<?, ?> h, String type) {
        String err = optionalString(h, "if",
            "if 字段必须为 String (CC IfConditionSchema z.string(), hooks.ts:19-27)");
        if (err != null) return err;
        err = optionalPositiveNumber(h, "timeout",
            "timeout 字段必须为正数 (CC z.number().positive(), hooks.ts:42-46)");
        if (err != null) return err;
        err = optionalString(h, "statusMessage",
            "statusMessage 字段必须为 String (CC z.string(), hooks.ts:47-50)");
        if (err != null) return err;
        err = optionalBoolean(h, "once",
            "once 字段必须为 Boolean (CC z.boolean(), hooks.ts:51-54)");
        if (err != null) return err;
        switch (type) {
            case "command":
                if (h.containsKey("shell")) {
                    Object shell = h.get("shell");
                    if (!(shell instanceof String s) || !SHELL_TYPES.contains(s)) {
                        return "shell 必须为 'bash'/'powershell' (CC z.enum(SHELL_TYPES), hooks.ts:36-41)";
                    }
                }
                err = optionalBoolean(h, "async",
                    "async 字段必须为 Boolean (CC z.boolean(), hooks.ts:55-58)");
                if (err != null) return err;
                return optionalBoolean(h, "asyncRewake",
                    "asyncRewake 字段必须为 Boolean (CC z.boolean(), hooks.ts:59-64)");
            case "prompt":
            case "agent":
                return optionalString(h, "model",
                    "model 字段必须为 String (CC z.string(), hooks.ts:81-86)");
            case "http":
                if (h.containsKey("headers")) {
                    Object headers = h.get("headers");
                    if (!(headers instanceof Map<?, ?> m)) {
                        return "headers 必须为 string→string 记录 (CC z.record(z.string(), z.string()), hooks.ts:106-111)";
                    }
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                            return "headers 键/值必须为 String (CC z.record(z.string(), z.string()), hooks.ts:106-111)";
                        }
                    }
                }
                if (h.containsKey("allowedEnvVars")) {
                    Object allowed = h.get("allowedEnvVars");
                    if (!(allowed instanceof List<?> list)) {
                        return "allowedEnvVars 必须为 String 数组 (CC z.array(z.string()), hooks.ts:112-117)";
                    }
                    for (Object item : list) {
                        if (!(item instanceof String)) {
                            return "allowedEnvVars 元素必须为 String (CC z.array(z.string()), hooks.ts:112-117)";
                        }
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /** zod .optional() 类型级校验：键存在即须 String（null 亦拒绝，CC 只接受缺省/undefined）。 */
    private static String optionalString(Map<?, ?> h, String key, String errMsg) {
        return h.containsKey(key) && !(h.get(key) instanceof String) ? errMsg : null;
    }

    /** zod .optional() 类型级校验：键存在即须 Boolean（null 亦拒绝）。 */
    private static String optionalBoolean(Map<?, ?> h, String key, String errMsg) {
        return h.containsKey(key) && !(h.get(key) instanceof Boolean) ? errMsg : null;
    }

    /** zod {@code z.number().positive().optional()}：键存在即须正数（null/String 亦拒绝）。 */
    private static String optionalPositiveNumber(Map<?, ?> h, String key, String errMsg) {
        if (h.containsKey(key)) {
            Object v = h.get(key);
            if (!(v instanceof Number n) || !(n.doubleValue() > 0)) {
                return errMsg;
            }
        }
        return null;
    }

    /** zod {@code z.string().url()}（require_protocol 等价）：URI 可解析且含合法 scheme。 */
    private static boolean isValidUrl(String s) {
        try {
            java.net.URI uri = java.net.URI.create(s);
            String scheme = uri.getScheme();
            return scheme != null && scheme.matches("[a-zA-Z][a-zA-Z0-9+.-]*");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析单个 HookMatcher · 对齐 CC HookMatcherSchema (hooks.ts:194-204).
     *
     * <p>{@code matcher?} (:196-199) 可选字符串; {@code hooks} (:200-202) HookCommand 数组.
     */
    @SuppressWarnings("unchecked")
    private static HookMatcher parseMatcher(Map<String, Object> map) {
        if (map == null) return null;
        Object matcherObj = map.get("matcher");
        // [IMP-HOOKS-S8 CCJ-HOOKS-T8-03] 缺省 ''（对齐 CC registerFrontmatterHooks.ts:48
        //   {@code matcherConfig.matcher ?? ''}）—— 旧缺省 '*' 泄漏到 getSessionHooks
        //   持久化/视图；匹配语义两者等价（matchesPattern 空/'*' 均 true），存储表示必须 ''.
        String matcher = matcherObj != null ? matcherObj.toString() : "";
        Object hooksObj = map.get("hooks");
        if (!(hooksObj instanceof List<?> rawList)) {
            return new HookMatcher(matcher, List.of());
        }
        List<HookCommand> commands = new ArrayList<>();
        for (Object h : rawList) {
            if (h instanceof Map<?, ?> hm) {
                HookCommand cmd = parseHookCommand(asStringMap(hm));
                if (cmd != null) commands.add(cmd);
            }
        }
        return new HookMatcher(matcher, commands);
    }

    /**
     * 解析单个 HookCommand · 对齐 CC HookCommandSchema (hooks.ts:32-163) discriminatedUnion('type').
     *
     * <p>按 {@code type} 字段路由到 4 子类 record (command/prompt/http/agent), 每个字段 JavaDoc 标注
     * CC 原名 + 行号. 未知 type -> null + log.warn.
     */
    private static HookCommand parseHookCommand(Map<String, Object> map) {
        if (map == null) return null;
        Object typeObj = map.get("type");
        String type = typeObj != null ? typeObj.toString() : "command";
        switch (type) {
            case "command":
                // CC BashCommandHookSchema (hooks.ts:32-65)
                return new CommandHook(
                    asString(map.get("command")),           // :34
                    asString(map.get("if")),                // :35 (ifCondition)
                    asString(map.get("shell")),             // :36
                    asInt(map.get("timeout")),              // :42
                    asString(map.get("statusMessage")),     // :47
                    asBool(map.get("once")),                // :51
                    asBool(map.get("async")),               // :55 (asyncFlag)
                    asBool(map.get("asyncRewake")));        // :59
            case "prompt":
                // CC PromptHookSchema (hooks.ts:67-95)
                return new PromptHook(
                    asString(map.get("prompt")),            // :69
                    asString(map.get("if")),                // :74
                    asInt(map.get("timeout")),              // :75
                    asString(map.get("model")),             // :81
                    asString(map.get("statusMessage")),     // :87
                    asBool(map.get("once")));               // :91
            case "http":
                // CC HttpHookSchema (hooks.ts:97-126)
                return new HttpHook(
                    asString(map.get("url")),               // :99
                    asString(map.get("if")),                // :100
                    asInt(map.get("timeout")),              // :101
                    asStringMap(map.get("headers")),        // :106
                    asStringList(map.get("allowedEnvVars")),// :112
                    asString(map.get("statusMessage")),     // :118
                    asBool(map.get("once")));               // :122
            case "agent":
                // CC AgentHookSchema (hooks.ts:128-163)
                return new AgentHook(
                    asString(map.get("prompt")),            // :138
                    asString(map.get("if")),                // :143
                    asInt(map.get("timeout")),              // :144
                    asString(map.get("model")),             // :149
                    asString(map.get("statusMessage")),     // :155
                    asBool(map.get("once")));               // :159
            default:
                log.warn("[FrontmatterHooks] 未知 hook type '{}', 跳过 (CC 支持 command/prompt/http/agent)", type);
                return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 · 对齐 CC registerFrontmatterHooks.ts:18-67
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 注册 Agent frontmatter hooks (isAgent=true 默认) · 4 参便利重载.
     *
     * <p>唯一生产调用方 SubagentExecutor (agent 场景) 用此重载, 对齐 CC registerFrontmatterHooks.ts:40
     * {@code if (isAgent && event==='Stop')} -- agent 场景 Stop -> SubagentStop.
     *
     * <p>[IMPL-10] DEL-L03-04: 注册落到 SessionHookStore（{@link HookRegistry#addSessionHook}，
     * 对齐 CC registerFrontmatterHooks.ts:56 {@code addSessionHook(sessionId=agentId, ...)}），
     * 不再走 GenericHook 旧会话作用域机制。
     *
     * @param hooks 事件 -> 匹配器列表 (来自 {@link #fromMap})
     * @return 实际注册的 hook 命令数
     */
    public static int register(
            HookRegistry hookRegistry,
            String agentId,
            String agentType,
            Map<HookEventType, List<HookMatcher>> hooks) {
        return register(hookRegistry, agentId, agentType, hooks, true);
    }

    /**
     * 注册 frontmatter hooks · 对齐 CC registerFrontmatterHooks.ts:18-67.
     *
     * <p><b>[Session H12 v2 Gap5 修复]</b> CC L40 {@code if (isAgent && event === 'Stop')}
     * 条件转换: 仅 {@code isAgent=true} 时 Stop -> SubagentStop. {@code isAgent=false}
     * (skill/普通 frontmatter, CC 默认) 时 Stop 保持 Stop.
     *
     * <p>遍历 {@code Map<HookEventType, List<HookMatcher>>} 全部事件 (27 事件支持, 对齐 CC
     * partialRecord 直接按事件名分发), 每个事件下每个 matcher 调 {@link #registerMatchers}.
     *
     * @param isAgent CC original: isAgent (registerFrontmatterHooks.ts:23, 默认 false)
     *                - true 时 Stop -> SubagentStop 转换
     * @return 实际注册的 hook 命令数
     */
    public static int register(
            HookRegistry hookRegistry,
            String agentId,
            String agentType,
            Map<HookEventType, List<HookMatcher>> hooks,
            boolean isAgent) {

        if (hookRegistry == null || hooks == null || hooks.isEmpty()) return 0;

        int registeredCount = 0;

        // 对齐 CC registerFrontmatterHooks.ts:40-45: 仅 isAgent=true 时 Stop -> SubagentStop
        HookEventType stopEventType = isAgent ? HookEventType.SUBAGENT_STOP : HookEventType.STOP;

        for (Map.Entry<HookEventType, List<HookMatcher>> entry : hooks.entrySet()) {
            HookEventType rawType = entry.getKey();
            // Stop 事件按 isAgent 转换 (CC partialRecord 直接按事件名分发无此转换, Java 扩展保留)
            HookEventType effectiveType = (isAgent && rawType == HookEventType.STOP)
                ? stopEventType : rawType;
            for (HookMatcher matcher : entry.getValue()) {
                registeredCount +=
                    registerMatchers(hookRegistry, effectiveType, matcher, agentId, agentType);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[FrontmatterHooks] 注册完成: agent={} ({}) 共 {} 个 hook, isAgent={}",
                agentId, agentType, registeredCount, isAgent);
        }
        log.info("[FrontmatterHooks] 注册完成: agent={} ({}) 共 {} 个 frontmatter hook (事件数={})",
            agentId, agentType, registeredCount, hooks.size());
        return registeredCount;
    }
    /**
     * 注册单个 matcher 的所有 hook 命令 · 对齐 CC registerFrontmatterHooks.ts:30-55.
     *
     * <p><b>[Session S1 P1-4]</b> matcher.hooks() 现为 {@code List<HookCommand>} (非 List&lt;String&gt;),
     * {@link HookCommand#hookType()} 4 类型 <b>全部注册</b>:
     * <ul>
     *   <li><b>[IMP-HOOKS-S8 H7 CCJ-HOOKS-T8-01]</b> {@code COMMAND}/{@code PROMPT}/{@code AGENT}/
     *       {@code HTTP} 一律注册到 SessionHookStore（{@link HookRegistry#addSessionHook}，
     *       对齐 CC registerFrontmatterHooks.ts:55-58 {@code for (const hook of hooksArray)
     *       addSessionHook(...)} 无类型过滤）—— 旧实现仅 COMMAND 注册、其余 log.warn 跳过的
     *       过滤分支已删除；执行分发走 HookRegistry.executeOneConfiguredHook 4 分支
     *       （executeConfiguredPrompt/Agent/Http，T4 域）</li>
     *   <li>{@code COMMAND} 保留 command 字段必填 fail-loud 守卫（镜像 CC
     *       BashCommandHookSchema {@code command: z.string()} 必填, schemas/hooks.ts:34；
     *       zod 校验失败即 hook 配置非法）</li>
     * </ul>
     *
     * <p>[IMPL-10] DEL-L03-04: 注册 key = agentId（对齐 CC registerFrontmatterHooks.ts:13
     * "session ID for agents" → addSessionHook(sessionId=agentId)）；matcher 语义由
     * {@link com.nexusai.application.agent.permission.hook.HookMatcherEngine#matchesSessionMatcher}
     * 承担（CC getMatchingHooks matchQuery 规则，含 SessionStart→source 等）。旧自定义
     * executeCommand 精简协议（无 shell/无 if/无 async）已删除，统一走 CommandHookExecutor。
     *
     * @return 实际注册的 hook 命令数（空 = 无 hook 注册）
     */
    private static int registerMatchers(
            HookRegistry hookRegistry, HookEventType eventType,
            HookMatcher matcher, String agentId, String agentType) {
        if (matcher.hooks().isEmpty()) return 0;

        int registeredCount = 0;
        List<HookCommand> hookCommands = matcher.hooks();
        for (int hookIndex = 0; hookIndex < hookCommands.size(); hookIndex++) {
            HookCommand cmd = hookCommands.get(hookIndex);
            String hookName = "frontmatter-" + agentId + "-" + eventType.name().toLowerCase()
                    + "-" + matcher.matcher() + "-" + hookIndex;

            // [IMP-HOOKS-S8 H7] COMMAND 类型 command 字段必填 fail-loud（CC zod command 必填）；
            // prompt/http/agent 无必填命令字段守卫（CC PromptHookSchema prompt/HttpHookSchema url/
            // AgentHookSchema prompt 同样必填, zod 校验层职责 — Java 解析层宽松保留, 执行层见 T4）。
            if (cmd instanceof CommandHook commandHook) {
                String command = commandHook.command();
                if (command == null || command.isBlank()) {
                    log.warn("[FrontmatterHooks] command hook 缺 command 字段, 跳过 (hook={}, agent={})",
                        hookName, agentType);
                    continue;
                }
            }

            // [IMPL-10] DEL-L03-04: 注册到 SessionHookStore（CC addSessionHook 等价），
            //   key=agentId；once/onHookSuccess 传 null（对齐 CC registerFrontmatterHooks 5 参调用）。
            // [IMP-HOOKS-S8 H7] 4 类型无过滤全注册；matcher 缺省 '' 由 parseMatcher 保证非 null。
            hookRegistry.addSessionHook(
                    agentId, eventType,
                    matcher.matcher() == null ? "" : matcher.matcher(),
                    cmd,
                    null,
                    null);
            registeredCount++;
            if (log.isDebugEnabled()) {
                log.debug("[FrontmatterHooks] 注册 session hook: event={} matcher={} type={} agent={}",
                    eventType, matcher.matcher(), cmd.hookType(), agentType);
            }
        }
        return registeredCount;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMPL-10] DEL-L03-04: 旧自定义执行链已删除（executeCommand/parseStdoutJson/
    //   buildStdinPayload/toCcEventName/matchesPattern）— frontmatter hooks 现走
    //   SessionHookStore → executeOneConfiguredHook → CommandHookExecutor 标准 CC 协议链。
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // 类型转换辅助 · frontmatter raw Map -> HookCommand 子类字段
    // ════════════════════════════════════════════════════════════════════════

    private static String asString(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private static Integer asInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static Boolean asBool(Object obj) {
        if (obj instanceof Boolean b) return b;
        if (obj instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object obj) {
        if (!(obj instanceof List<?> list)) return null;
        List<String> result = new ArrayList<>();
        for (Object o : list) {
            if (o != null) result.add(o.toString());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue() == null ? null : e.getValue().toString());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }
}
