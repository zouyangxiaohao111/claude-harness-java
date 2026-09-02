package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 配置驱动 hook 匹配引擎 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks.ts:1603-1874}
 * {@code getMatchingHooks} + {@code matchesPattern} (:1346-1381).
 *
 * <p>WHY (H1 主链路打通): settings 多来源 hooks 配置经 {@link MultiSourceHooksConfigLoader} 写入
 * {@link HooksSettings} (bySource) → {@link HooksConfigSnapshot} 捕获快照 → 本引擎在
 * {@link HookRegistry#getMatchingHooks(HookEvent)} 入口被调, 从快照取 matcher 并执行
 * CC getMatchingHooks 的 7 步算法 (CC hooks.ts:1603-1874):
 * <ol>
 *   <li>取 config: {@code getHooksConfigFromSnapshot().getOrDefault(event.type(), [])}
 *       (H1 只接通 snapshot 来源; CC 5 类来源缩减见 Concern H1-2)</li>
 *   <li>{@link #extractMatchQuery(HookEvent)}: 27 事件字段映射 (CC hooks.ts:1615-1670)</li>
 *   <li>matcher 过滤: {@code matchQuery == null ? 全部 : matchesPattern} (CC :1684;
 *       matcher 为 null/空 也保留)</li>
 *   <li>flatMap 成 {@link MatchedHook}: hookSource="settings", plugin/skill 全 null (H1 范围)</li>
 *   <li>4 类去重 (command/prompt/agent/http; CC hooks.ts:1735-1806 的 callback/function 为
 *       SDK-only, Java 无): 按类型分 4 个独立 Map 去重 (同文本 prompt/agent 是不同 hook),
 *       输出顺序 = CC {@code [command, prompt, agent, http]} 分组</li>
 *   <li>if 条件过滤 (CC hooks.ts:1808-1848): 仅工具事件可求值, 非工具事件有 if → 过滤</li>
 *   <li>HTTP 排除 (CC hooks.ts:1850-1864): SessionStart/Setup 事件过滤 HTTP hook</li>
 * </ol>
 *
 * <p><b>H1 范围简化</b> (Concern H1-2): 仅 settings 来源 (plugin/skill 来源留 H12).
 * if ruleContent 内容匹配对齐 CC {@code tool.preparePermissionMatcher} — [G3] 经
 * {@link #prepareContentMatcher(HookEvent)} 按事件工具解析 Tool 实例分发（Bash 子命令拆分
 * + 前缀/通配, 路径工具 glob, 无 matcher 工具 → 过滤）。
 *
 * <p>日志: slf4j 中文, debug 用 {@code if (log.isDebugEnabled())} 包裹.
 *
 */
@Component
public class HookMatcherEngine {

    private static final Logger log = LoggerFactory.getLogger(HookMatcherEngine.class);

    private final HooksConfigSnapshot hooksConfigSnapshot;
    private final PermissionRuleValueParser permissionRuleValueParser;

    /**
     * [G3] 工具注册表 · 解析 hook 事件的 tool_name → Tool 实例，调用其
     * {@link Tool#preparePermissionMatcher(JsonNode)}（对齐 CC hooks.ts:1402
     * {@code findToolByName(tools, hookInput.tool_name)}）。
     *
     * <p>用 volatile + setter（镜像 {@code HookRegistry.setHookMatcherEngine} 模式），
     * 保持构造器签名不变（纯增量）。{@code @Autowired(required=false)}：Spring 上下文自动
     * 注入（ToolRegistry 是 {@code @Component}）；手动 new 场景为 null → 无工具可解析 →
     * ruleContent 非空即 false（对齐 CC patternMatcher undefined 语义，hooks.ts:1419）。
     */
    private volatile ToolRegistry toolRegistry;

    @Autowired(required = false)
    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        if (log.isDebugEnabled()) {
            log.debug("HOOK toolRegistry injected: present={}", toolRegistry != null);
        }
    }

    /**
     * [H-WF2-01] 工具输入 safeParse 校验器 · CC original: {@code inputSchema.safeParse}
     * （hooks.ts:1405 {@code tool?.inputSchema.safeParse(hookInput.tool_input)}）。
     *
     * <p>复用 {@link ToolInputValidator#safeParseSchema}（Zod 等价校验：required 缺失 /
     * 字段类型错 / unknown keys）作为 {@code prepareIfConditionMatcher} 的 safeParse 门禁
     * ——CC 仅当 {@code input?.success} 才取 {@code preparePermissionMatcher(input.data)}，
     * 非法 tool_input 下 matcher 为 undefined → ruleContent 非空即 false（过滤）。
     *
     * <p>一处建 validator、两处引用（X-WF2-03 §6）：{@link ToolInputValidator} 已是工具执行
     * 链路（toolOrchestration 等价）的唯一 schema 校验器，本引擎与之共享同一语义，避免
     * hooks 域与工具域各写一套校验产生 strict/宽松漂移。
     *
     * <p>{@code @Autowired(required=false)}：Spring 上下文自动注入；手动 new 场景（测试）为
     * null → 跳过门禁（与 toolRegistry 同为 null 容忍，生产恒注入）。
     */
    private volatile ToolInputValidator inputValidator;

    @Autowired(required = false)
    public void setInputValidator(ToolInputValidator inputValidator) {
        this.inputValidator = inputValidator;
        if (log.isDebugEnabled()) {
            log.debug("HOOK inputValidator injected: present={}", inputValidator != null);
        }
    }

    /**
     * @param hooksConfigSnapshot     快照层 (getHooksConfigFromSnapshot)
     * @param permissionRuleValueParser 权限规则解析器 (if 条件 + legacy 归一化)
     */
    public HookMatcherEngine(HooksConfigSnapshot hooksConfigSnapshot,
                             PermissionRuleValueParser permissionRuleValueParser) {
        this.hooksConfigSnapshot = hooksConfigSnapshot;
        this.permissionRuleValueParser = permissionRuleValueParser;
    }

    /**
     * registered 源 hook matcher · 对齐 CC {@code PluginHookMatcher}
     * (Open-ClaudeCode/src/utils/settings/types.ts PluginHookMatcher, loadPluginHooks.ts:74-81)
     * + {@code HookCallbackMatcher} (types/hooks.ts:228-232, SDK callback 含可选 matcher).
     *
     * <p>WHY (MT-02 / OPD-WF2-MT-02): CC getHooksConfig 把 registered 源（SDK callback +
     * plugin native hooks）并入 getMatchingHooks 统一单链 (hooks.ts:1519-1529), 每个 registered
     * matcher 与 settings matcher 一样参与统一 matcher 过滤 (hooks.ts:1681-1686 matchesPattern)
     * 与去重/if (hooks.ts:1735-1848). 旧 Java 插件 hooks 在 PluginLoader.matchesMatcher 独立
     * 过滤、不经 getMatchingHooks (探查 WF2-01 H4/H5/J3 △). 本 record 承载 registered 源的
     * matcher 串 + plugin/skill context + CommandHooks, 供 {@link HookRegistry#getMatchingHooks}
     * 构造并传入 {@link #getMatchingHooks(HookEvent, java.util.List, java.util.List)}.
     *
     * <p><b>字段来源对照</b>:
     * <ul>
     *   <li>{@code matcher} — CC original: {@code PluginHookMatcher.matcher}
     *       (loadPluginHooks.ts:74-81); null/空 = 匹配全部 (CC :1684)</li>
     *   <li>{@code pluginRoot} — CC original: {@code PluginHookMatcher.pluginRoot};
     *       hookSource 三元 plugin 分支依据 (hooks.ts:1694-1702)</li>
     *   <li>{@code pluginId} — CC original: {@code PluginHookMatcher.pluginId};
     *       pluginHookCounts 分类 (hooks.ts:1461-1478)</li>
     *   <li>{@code pluginName} — CC original: {@code PluginHookMatcher.pluginName};
     *       hookSource 'plugin:name' 分支 (hooks.ts:1696-1697)</li>
     *   <li>{@code skillRoot} — CC original: {@code SkillHookMatcher.skillRoot};
     *       hookSource 'skill' 分支 (hooks.ts:1698-1701)</li>
     *   <li>{@code hooks} — CC original: {@code matcher.hooks} (CommandHook 数组)</li>
     * </ul>
     *
     * @param matcher    CC original: matcher (loadPluginHooks.ts:74-81); null/空 = 匹配全部
     * @param pluginRoot CC original: pluginRoot (PluginHookMatcher); null = 非插件源
     * @param pluginId   CC original: pluginId (PluginHookMatcher); null = 非插件源
     * @param pluginName CC original: pluginName (PluginHookMatcher); null = 非插件源
     * @param skillRoot  CC original: skillRoot (SkillHookMatcher); null = 非 skill 源
     * @param hooks      CC original: matcher.hooks (CommandHook 数组, 非空)
     */
    public record RegisteredHookMatcher(
            String matcher,
            String pluginRoot,
            String pluginId,
            String pluginName,
            String skillRoot,
            java.util.List<HookCommand> hooks) {
        public RegisteredHookMatcher {
            if (hooks == null) {
                hooks = java.util.List.of();
            }
            hooks = java.util.List.copyOf(hooks);
        }
    }

    /**
     * 获取匹配事件的 hook · 等价 CC getMatchingHooks (hooks.ts:1603-1874).
     *
     * <p>WHY: 7 步算法见类级 JavaDoc. 顶层 try/catch 对齐 CC :1872-1873
     * (任何异常 → 返回空列表, 不中断调用方).
     *
     * @param event hook 事件
     * @return 匹配的 MatchedHook 列表 (可能为空, 永不 null)
     */
    public List<MatchedHook> getMatchingHooks(HookEvent event) {
        return getMatchingHooks(event, java.util.List.of());
    }

    /**
     * 获取匹配事件的 hook · 等价 CC getMatchingHooks (hooks.ts:1603-1874) +
     * [IMPL-07 OD-11] session hooks 并入统一匹配链.
     *
     * <p>WHY (OD-11 ADJUDICATED): CC getHooksConfig 把 snapshot + registered + session
     * 合并成单链 (hooks.ts:1492-1566), 随后 getMatchingHooks 对<b>全集合</b>去重
     * (hookDedupKey :1453-1455: 源前缀+payload, settings/session 同 '' 前缀折叠,
     * new Map 保留最后一条 → session last-wins). 旧 Java 引擎只对 settings 快照去重,
     * session hook 经 executeSessionHooks 分链执行 → 同命令双执行. 本重载把调用方
     * 传入的 session MatchedHook 并入 flatMap 产物之后、去重之前, 与 settings 同键折叠.
     *
     * <p>7 步算法 (CC hooks.ts:1603-1874):
     * <ol>
     *   <li>取 config: {@code getHooksConfigFromSnapshot().getOrDefault(event.type(), [])}
     *       (H1 只接通 snapshot 来源; CC 5 类来源缩减见 Concern H1-2; null 快照 → 空,
     *       不吞 session 合并)</li>
     *   <li>{@link #extractMatchQuery(HookEvent)}: 27 事件字段映射 (CC hooks.ts:1615-1670)</li>
     *   <li>matcher 过滤: {@code matchQuery == null ? 全部 : matchesPattern} (CC :1684;
     *       matcher 为 null/空 也保留)</li>
     *   <li>flatMap 成 {@link MatchedHook}: hookSource="settings", plugin/skill 全 null (H1 范围);
     *       [IMPL-07] 追加 session MatchedHook (extraMatched, 调用方已按 matchesSessionMatcher 过滤)</li>
     *   <li>4 类去重 (command/prompt/agent/http; CC hooks.ts:1735-1806 的 callback/function 为
     *       SDK-only, Java 无): 按类型分 4 个独立 Map 去重 (同文本 prompt/agent 是不同 hook),
     *       输出顺序 = CC {@code [command, prompt, agent, http]} 分组; session 追加于 settings
     *       之后 → 同 '' 前缀同 payload 折叠为一条且 session 胜出 (对齐 CC new Map last-wins)</li>
     *   <li>if 条件过滤 (CC hooks.ts:1808-1848): 仅工具事件可求值, 非工具事件有 if → 过滤</li>
     *   <li>HTTP 排除 (CC hooks.ts:1850-1864): SessionStart/Setup 事件过滤 HTTP hook</li>
     * </ol>
     *
     * @param event       hook 事件
     * @param extraMatched 额外匹配 hook (session 作用域 command hooks; null/空 → 纯 settings 匹配)
     * @return 匹配的 MatchedHook 列表 (可能为空, 永不 null)
     */
    public List<MatchedHook> getMatchingHooks(HookEvent event, java.util.List<MatchedHook> extraMatched) {
        return getMatchingHooks(event, extraMatched, java.util.List.of());
    }

    /**
     * 获取匹配事件的 hook · 等价 CC getMatchingHooks (hooks.ts:1603-1874) +
     * [IMPL-07 OD-11] session hooks 并入统一匹配链 +
     * [MT-02] registered/插件 hooks 并入统一单链.
     *
     * <p>WHY (MT-02 ADJUDICATED / OPD-WF2-MT-02): CC getHooksConfig 把 snapshot + registered
     * (SDK callback + plugin native) + session 合并成单链 (hooks.ts:1492-1566), 随后
     * getMatchingHooks 对<b>全集合</b>统一 matcher 过滤 / 去重 / if (hooks.ts:1603-1874).
     * 旧 Java 引擎只处理 settings 快照 + session, registered 源（PluginLoader 插件 native
     * hooks + programmatic hooks）在 PluginLoader.matchesMatcher 独立过滤、不经 getMatchingHooks
     * (探查 WF2-01 H4/H5/J3 △). 本重载把调用方传入的 registered matchers (含 matcher +
     * plugin/skill context) 并入 matcher 列表 <b>matcher 过滤之前</b>, 与 settings 同源统一
     * matcher 过滤 / 去重 / if (CC :1681-1686 的 filter 作用于含 registered 的全集合) →
     * 使 {@code getMatchingHooks} 返回集对齐 CC 单链 (跨模块消费 遥测 / executeEvent 聚合 /
     * Stop 基于统一链).
     *
     * @param event             hook 事件
     * @param extraMatched      额外匹配 hook (session 作用域 command hooks; null/空 → 纯 settings 匹配)
     * @param registeredMatchers registered 源 matchers (PluginLoader 插件 native hooks +
     *                           programmatic hooks; 调用方已按 managedOnly 门控过滤, 见
     *                           {@link HookRegistry#getMatchingHooks(HookEvent)}; null/空 → 不并入)
     * @return 匹配的 MatchedHook 列表 (可能为空, 永不 null)
     */
    public List<MatchedHook> getMatchingHooks(HookEvent event, java.util.List<MatchedHook> extraMatched,
                                              java.util.List<RegisteredHookMatcher> registeredMatchers) {
        try {
            // 1. 取 config (H1: 仅 snapshot 来源; null 快照 → 空, 不吞 session/registered 合并)
            List<HookMatcher> hookMatchers = hooksConfigSnapshot == null
                ? List.of()
                : hooksConfigSnapshot.getHooksConfigFromSnapshot()
                    .getOrDefault(event.type(), List.of());

            // 2. 提取 matchQuery (27 事件各用不同字段)
            String matchQuery = extractMatchQuery(event);
            if (log.isDebugEnabled()) {
                log.debug("getMatchingHooks: event={} matchQuery={} 快照中 matcher 总数={} registered={}",
                    event.type(), matchQuery, hookMatchers.size(),
                    registeredMatchers == null ? 0 : registeredMatchers.size());
            }

            // 3. matcher 过滤 (CC :1684): matchQuery==null → 全部; 否则 matchesPattern
            //    (CC 还要求 !matcher.matcher 也保留, 即 matcher 为 null/空 不参与过滤)
            List<HookMatcher> filteredMatchers = new ArrayList<>();
            if (matchQuery == null) {
                filteredMatchers.addAll(hookMatchers);
            } else {
                for (HookMatcher m : hookMatchers) {
                    if (m.matcher() == null || m.matcher().isEmpty()
                            || matchesPattern(matchQuery, m.matcher())) {
                        filteredMatchers.add(m);
                    }
                }
            }

            // 4. flatMap 成 MatchedHook (CC :1690-1740): hookSource="settings" (H1 范围)
            List<MatchedHook> matchedHooks = new ArrayList<>();
            for (HookMatcher m : filteredMatchers) {
                for (HookCommand hook : m.hooks()) {
                    matchedHooks.add(new MatchedHook(hook, null, null, null, "settings"));
                }
            }
            // [MT-02] registered/插件 matchers 统一 matcher 过滤 (CC :1681-1686 filter 作用于全集合)
            if (registeredMatchers != null) {
                for (RegisteredHookMatcher rm : registeredMatchers) {
                    if (matchQuery != null && rm.matcher() != null && !rm.matcher().isEmpty()
                            && !matchesPattern(matchQuery, rm.matcher())) {
                        continue;
                    }
                    String hookSource = rm.pluginRoot() != null
                        ? (rm.pluginName() != null ? "plugin:" + rm.pluginName() : "plugin")
                        : rm.skillRoot() != null ? "skill" : "settings";
                    for (HookCommand hook : rm.hooks()) {
                        matchedHooks.add(new MatchedHook(hook, rm.pluginRoot(), rm.pluginId(),
                            rm.skillRoot(), hookSource));
                    }
                }
            }
            // [IMPL-07 OD-11] session hooks 并入统一链 (去重之前; settings/registered 之后 → last-wins)
            if (extraMatched != null) {
                matchedHooks.addAll(extraMatched);
            }

            // 5. 去重 (CC :1742-1806): 原顺序去重, 同 key 保留最后一条
            List<MatchedHook> uniqueHooks = dedup(matchedHooks);

            // 6. if 条件过滤 (CC :1808-1848)
            List<MatchedHook> ifFilteredHooks = filterByIfCondition(event, uniqueHooks);

            // 7. HTTP 排除 (CC :1850-1864): SessionStart/Setup 过滤 HTTP hook
            List<MatchedHook> result = excludeHttpForSessionStartSetup(event.type(), ifFilteredHooks);

            if (log.isDebugEnabled()) {
                log.debug("getMatchingHooks: 命中 {} 个 unique hooks (去重前 {} 个)",
                    result.size(), matchedHooks.size());
            }
            return result;
        } catch (Exception e) {
            // 对齐 CC :1872-1873 (顶层 catch → [])
            log.warn("getMatchingHooks 异常, 返回空列表: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 提取 matchQuery · 等价 CC getMatchingHooks switch (hooks.ts:1615-1670).
     *
     * <p>WHY: 各事件用不同字段匹配 matcher (工具事件用 tool_name; 会话事件用 source/trigger;
     * FileChanged 用 file_path 的 basename; 其余无 matchQuery → null → 全部 matcher 通过).
     */
    private String extractMatchQuery(HookEvent event) {
        return switch (event.type()) {
            case PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE,
                 PERMISSION_REQUEST, PERMISSION_DENIED -> event.toolName();
            case SESSION_START, CONFIG_CHANGE -> dataStr(event, "source");
            case SETUP, PRE_COMPACT, POST_COMPACT -> dataStr(event, "trigger");
            case NOTIFICATION -> dataStr(event, "notification_type");
            case SESSION_END -> dataStr(event, "reason");
            case STOP_FAILURE -> {
                // CC hookInput.error (hooks.ts:1654) · [IMP-HOOKS-S5 D-18] error 现为字符串
                //   载荷（CC hooks.ts:3612 `lastMessage.error ?? 'unknown'`），String.valueOf
                //   对 String 恒等 → 实现不变，注释更新
                Object error = event.data().get("error");
                yield error != null ? String.valueOf(error) : null;
            }
            case SUBAGENT_START, SUBAGENT_STOP -> dataStr(event, "agent_type");
            case ELICITATION, ELICITATION_RESULT -> dataStr(event, "mcp_server_name");
            case INSTRUCTIONS_LOADED -> dataStr(event, "load_reason");
            case FILE_CHANGED -> {
                String path = dataStr(event, "file_path");
                yield path != null ? basename(path) : null;
            }
            // TeammateIdle/TaskCreated/TaskCompleted/其他 → null (CC :1665-1668 无 matchQuery)
            // [编译协调修复] STATUS_LINE/FILE_SUGGESTION (IMP-CF-03 新增事件): CC 无对应 matcher
            //   语义 (executeStatusLineCommand/executeFileSuggestionCommand 按配置存在执行, 非 matcher 匹配)
            //   → 与其他无 matchQuery 事件同归 null
            case TEAMMATE_IDLE, TASK_CREATED, TASK_COMPLETED, STOP, USER_PROMPT_SUBMIT,
                 WORKTREE_CREATE, WORKTREE_REMOVE, CWD_CHANGED,
                 STATUS_LINE, FILE_SUGGESTION -> null;
        };
    }

    /** data map 取字符串值 · null 透传. */
    private static String dataStr(HookEvent event, String key) {
        Object v = event.data().get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /** 取路径 basename · 去掉最后 / 或 \ 前缀 (CC node path.basename). */
    private static String basename(String path) {
        if (path == null || path.isEmpty()) return path;
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * matcher 模式匹配 · 等价 CC matchesPattern (hooks.ts:1346-1381).
     *
     * <p>WHY: CC 三级: 1) null/空/"*" → true; 2) 纯 {@code [a-zA-Z0-9_|]+} (无正则特殊字符)
     * → 精确/管道列表; 3) 否则按正则 (JS {@code test} = find 语义, 含 legacy 别名回退).
     */
    boolean matchesPattern(String matchQuery, String matcher) {
        if (matcher == null || matcher.isEmpty() || "*".equals(matcher)) {
            return true;
        }
        // 2) 纯字母数字下划线竖线 → 精确 / 管道列表 (CC :1351-1362)
        if (matcher.matches("[a-zA-Z0-9_|]+")) {
            if (matcher.contains("|")) {
                // CC :1354-1359: matcher.split('|').map(normalizeLegacyToolName(p.trim())) →
                // patterns.includes(matchQuery). matchQuery 保持<b>原始值</b> (CC 仅归一化
                // matcher 侧, 不归一化 query; 对抗核验发现 Java 曾把 query 也归一化 → 更宽容).
                for (String p : matcher.split("\\|")) {
                    String norm = permissionRuleValueParser.normalizeLegacyToolName(p.trim());
                    if (matchQuery.equals(norm)) {
                        return true;
                    }
                }
                return false;
            }
            // 简单精确 (CC :1361-1362: matchQuery === normalizeLegacyToolName(matcher))
            return matchQuery.equals(permissionRuleValueParser.normalizeLegacyToolName(matcher));
        }
        // 3) 正则路径 (CC :1364-1378): 对 matchQuery test + 对 legacy 别名逐一 test; 非法 → false
        try {
            Pattern regex = Pattern.compile(matcher);
            if (regex.matcher(matchQuery).find()) {
                return true;
            }
            for (String legacyName : permissionRuleValueParser.getLegacyToolNames(matchQuery)) {
                if (regex.matcher(legacyName).find()) {
                    return true;
                }
            }
            return false;
        } catch (PatternSyntaxException e) {
            if (log.isDebugEnabled()) {
                log.debug("Hook matcher 中的正则表达式非法, 返回 false: {}", matcher);
            }
            return false;
        }
    }

    /**
     * 4 类去重 · 等价 CC getMatchingHooks 去重段 (hooks.ts:1735-1806).
     *
     * <p>WHY: 同 (event, matcher) 重复注册的 hook 只保留一条. CC 按类型分 <b>4 个独立 Map</b>
     * (uniqueCommandHooks/uniquePromptHooks/uniqueAgentHooks/uniqueHttpHooks, hooks.ts:1735-1795)
     * 去重 — 各类型 payload key 前缀不同 (command 比 shell+command+if; prompt/agent 比 prompt+if;
     * http 比 url+if). <b>同文本的 prompt 与 agent hook 是不同 hook</b> (CC :1757-1782 分离保留);
     * 若用单个 Map, prompt 与 agent 同 prompt+if 会因相同 key 被错误折叠成一条 (对抗核验发现).
     * 输出顺序 = CC :1799-1806 的 {@code [command, prompt, agent, http]} 分组 (非原交错顺序),
     * 组内 {@link LinkedHashMap} put 保留最后一条 (对齐 CC {@code new Map(entries).values()} last-wins).
     */
    private List<MatchedHook> dedup(List<MatchedHook> matchedHooks) {
        Map<String, MatchedHook> command = new LinkedHashMap<>();
        Map<String, MatchedHook> prompt = new LinkedHashMap<>();
        Map<String, MatchedHook> agent = new LinkedHashMap<>();
        Map<String, MatchedHook> http = new LinkedHashMap<>();
        for (MatchedHook m : matchedHooks) {
            Map<String, MatchedHook> target = switch (m.hook().hookType()) {
                case COMMAND -> command;
                case PROMPT -> prompt;
                case AGENT -> agent;
                case HTTP -> http;
            };
            target.put(dedupKey(m), m);
        }
        // CC :1799-1806: [...uniqueCommandHooks, ...uniquePromptHooks, ...uniqueAgentHooks, ...uniqueHttpHooks]
        List<MatchedHook> out = new ArrayList<>();
        out.addAll(command.values());
        out.addAll(prompt.values());
        out.addAll(agent.values());
        out.addAll(http.values());
        return out;
    }

    private String dedupKey(MatchedHook m) {
        // CC hookDedupKey (hooks.ts:1453-1455): `${pluginRoot ?? skillRoot ?? ''}\0${payload}`
        String ns = m.pluginRoot() != null ? m.pluginRoot() : m.skillRoot();
        String prefix = ns != null ? ns : "";
        String ifCond = ifOrEmpty(m.hook());
        String payload = switch (m.hook().hookType()) {
            case COMMAND -> {
                CommandHook c = (CommandHook) m.hook();
                // CC :1750-1752: shell 是身份一部分, 缺省默认 bash
                String shell = c.shell() != null ? c.shell() : CommandHook.DEFAULT_SHELL;
                yield shell + "\0" + c.command() + "\0" + ifCond;
            }
            case PROMPT -> ((PromptHook) m.hook()).prompt() + "\0" + ifCond;
            case AGENT -> ((AgentHook) m.hook()).prompt() + "\0" + ifCond;
            case HTTP -> ((HttpHook) m.hook()).url() + "\0" + ifCond;
        };
        return prefix + "\0" + payload;
    }

    /**
     * if 条件过滤 · 等价 CC getMatchingHooks if 段 (hooks.ts:1808-1848).
     *
     * <p>WHY: if 条件让 hook 只在特定工具/内容时运行 (如 "Bash(git *)" 只对 git 命令生效).
     * 仅 4 类 hook 有 if; 非工具事件上 ifMatcher 为 undefined → 有 if 的 hook 被过滤
     * (CC :1833-1836). 工具事件: 用 PermissionRuleValueParser 解析 if, 工具名比较 +
     * ruleContent 内容匹配.
     */
    private List<MatchedHook> filterByIfCondition(HookEvent event, List<MatchedHook> uniqueHooks) {
        boolean hasIfCondition = false;
        for (MatchedHook h : uniqueHooks) {
            if (isIfCapable(h.hook()) && hasIf(h.hook())) {
                hasIfCondition = true;
                break;
            }
        }
        // CC hooks.ts:1819-1820: 有 if 条件才 prepareIfConditionMatcher(事件级, 每事件一次)
        Predicate<String> contentMatcher = hasIfCondition ? prepareContentMatcher(event) : null;
        List<MatchedHook> result = new ArrayList<>();
        for (MatchedHook h : uniqueHooks) {
            if (!isIfCapable(h.hook()) || !hasIf(h.hook())) {
                result.add(h);
                continue;
            }
            String ifCondition = ifOf(h.hook());
            if (matchesIf(event, ifCondition, contentMatcher)) {
                result.add(h);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Hook 因 if 条件 \"{}\" 不匹配被过滤 (event={})", ifCondition, event.type());
                }
            }
        }
        return result;
    }

    /**
     * 事件级内容匹配器 · CC original: {@code prepareIfConditionMatcher}（hooks.ts:1390-1421）。
     *
     * <p>CC :1402-1409: {@code tool = findToolByName(tools, hookInput.tool_name)} →
     * {@code input?.success && tool?.preparePermissionMatcher ? await tool.preparePermissionMatcher(input.data)
     * : undefined}。Java 端经 {@link #toolRegistry} 解析 Tool 实例并调用
     * {@link Tool#preparePermissionMatcher(JsonNode)}。工具未注册 / 未实现 → null
     * （对齐 CC patternMatcher undefined → ruleContent 非空即 false，:1419）。
     *
     * @param event hook 事件
     * @return 内容匹配谓词（ruleContent → boolean）；无工具或工具未实现 → null
     */
    private Predicate<String> prepareContentMatcher(HookEvent event) {
        ToolRegistry registry = this.toolRegistry;
        if (registry == null) {
            return null;
        }
        Tool tool = registry.get(event.toolName()).orElse(null);
        if (tool == null) {
            return null;
        }
        // [H-WF2-01] safeParse 门禁 · CC original: hooks.ts:1405-1409
        //   const input = tool?.inputSchema.safeParse(hookInput.tool_input)
        //   const patternMatcher = input?.success && tool?.preparePermissionMatcher
        //     ? await tool.preparePermissionMatcher(input.data) : undefined
        // CC 仅 safeParse success 才取 matcher；非法 tool_input（必填键缺失/类型错/未知键）
        // → matcher undefined → ruleContent 非空即 false（:1419 过滤）。Java 复用
        // ToolInputValidator.safeParseSchema（与工具执行链路同一 validator，X-WF2-03 §6）。
        ToolInputValidator validator = this.inputValidator;
        if (validator != null) {
            ToolErrorFormatter.SafeParseResult parsed = validator.safeParseSchema(tool, event.input());
            if (!parsed.ok()) {
                if (log.isDebugEnabled()) {
                    log.debug("Hook 内容匹配器跳过: tool={} tool_input 未通过 inputSchema 校验 " +
                            "({} 个 issue), 对齐 CC safeParse 门禁 → 规则内容匹配 false",
                        tool.name(), parsed.issues().size());
                }
                return null;
            }
        }
        return tool.preparePermissionMatcher(event.input());
    }

    /**
     * 求值单个 if 条件 · 等价 CC prepareIfConditionMatcher 返回的闭包 (hooks.ts:1390-1421).
     *
     * <p>WHY: CC 闭包逻辑: 非工具事件 → undefined (此处直接 false); 解析 if →
     * normalizeLegacyToolName 工具名比较; ruleContent 为空 → true; 有 ruleContent →
     * 内容匹配器. Java 端把闭包内联为方法.
     */
    private boolean matchesIf(HookEvent event, String ifCondition, Predicate<String> contentMatcher) {
        // CC :1402-1408: 非工具事件 → ifMatcher undefined → 有 if 即被过滤
        if (!isIfToolEvent(event.type())) {
            if (log.isDebugEnabled()) {
                log.debug("Hook if 条件 \"{}\" 无法在非工具事件 {} 上求值, 过滤",
                    ifCondition, event.type());
            }
            return false;
        }
        PermissionRuleValue parsed = permissionRuleValueParser.parse(ifCondition);
        if (parsed == null) {
            // Java PermissionRuleValueParser.parse 失败返回 null (fail soft); 保守不匹配
            if (log.isDebugEnabled()) {
                log.debug("Hook if 条件 \"{}\" 解析失败, 过滤", ifCondition);
            }
            return false;
        }
        // CC :1412-1414: normalizeLegacyToolName(parsed.toolName) !== normalizeLegacyToolName(tool_name)
        String parsedTool = permissionRuleValueParser.normalizeLegacyToolName(parsed.toolName());
        String eventTool = permissionRuleValueParser.normalizeLegacyToolName(event.toolName());
        if (!parsedTool.equals(eventTool)) {
            return false;
        }
        // CC :1415-1417: !parsed.ruleContent → true (whole-tool if 无内容限定)
        if (parsed.ruleContent() == null || parsed.ruleContent().isEmpty()) {
            return true;
        }
        // CC :1418-1419: patternMatcher ? patternMatcher(parsed.ruleContent) : false — 内容匹配
        // 由事件工具经 preparePermissionMatcher prepare 的闭包承担; 无 matcher → false (过滤)
        return contentMatcher != null && contentMatcher.test(parsed.ruleContent());
    }

    /**
     * HTTP 排除 · 等价 CC getMatchingHooks 末尾 (hooks.ts:1850-1864).
     *
     * <p>WHY: HTTP hook 不支持 SessionStart/Setup 事件 (headless 模式下 sandbox ask 回调
     * 死锁 — structuredInput consumer 未启动). H1 仅 4 类, 只排 HTTP.
     */
    private List<MatchedHook> excludeHttpForSessionStartSetup(HookEventType type, List<MatchedHook> hooks) {
        if (type != HookEventType.SESSION_START && type != HookEventType.SETUP) {
            return hooks;
        }
        List<MatchedHook> result = new ArrayList<>();
        for (MatchedHook h : hooks) {
            if (h.hook().hookType() == HookCommand.HookType.HTTP) {
                if (log.isDebugEnabled()) {
                    log.debug("跳过 HTTP hook {} — HTTP hook 不支持 {} 事件",
                        ((HttpHook) h.hook()).url(), type);
                }
                continue;
            }
            result.add(h);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 小工具 (if 提取 / 内容匹配最小实现)
    // ════════════════════════════════════════════════════════════════════════

    /** 4 类 hook 是否有 if 字段 (command/prompt/agent/http; CC :1811-1818). */
    private static boolean isIfCapable(HookCommand h) {
        return h.hookType() == HookCommand.HookType.COMMAND
            || h.hookType() == HookCommand.HookType.PROMPT
            || h.hookType() == HookCommand.HookType.AGENT
            || h.hookType() == HookCommand.HookType.HTTP;
    }

    private static boolean hasIf(HookCommand h) {
        String s = ifOf(h);
        return s != null && !s.isEmpty();
    }

    private static String ifOf(HookCommand h) {
        return switch (h.hookType()) {
            case COMMAND -> ((CommandHook) h).ifCondition();
            case PROMPT -> ((PromptHook) h).ifCondition();
            case AGENT -> ((AgentHook) h).ifCondition();
            case HTTP -> ((HttpHook) h).ifCondition();
        };
    }

    private static String ifOrEmpty(HookCommand h) {
        String s = ifOf(h);
        return s != null ? s : "";
    }

    /**
     * 是否 if 可求值的工具事件 · CC prepareIfConditionMatcher (hooks.ts:1395-1401)
     * 仅 4 种: PreToolUse/PostToolUse/PostToolUseFailure/PermissionRequest.
     * 注意 PermissionDenied 不在其中 (CC 未列).
     */
    private static boolean isIfToolEvent(HookEventType type) {
        return type == HookEventType.PRE_TOOL_USE
            || type == HookEventType.POST_TOOL_USE
            || type == HookEventType.POST_TOOL_USE_FAILURE
            || type == HookEventType.PERMISSION_REQUEST;
    }

    /**
     * ruleContent 内容匹配 · 按事件工具对齐 CC {@code tool.preparePermissionMatcher}
     * （prepareIfConditionMatcher, hooks.ts:1406-1419）。
     *
     * <p>[G3] 集中实现已迁移到 Tool 接口扩展点：Bash 内容匹配 → {@code BashTool}，
     * 路径 glob → Read/Edit/Write/Glob/Grep 各工具。本引擎经 {@link #prepareContentMatcher(HookEvent)}
     * 按工具实例分发，无工具/未实现 → null（ruleContent 非空即过滤，:1419）。
     * 旧 {@code matchesContentByTool/matchBashContent/isBashTool/isPathTool/getTextField/
     * matchWildcardPattern/permissionRuleExtractPrefix} 集中逻辑已删除（DEL-G3-01，
     * BashRuleMatcher.matchWildcardPattern 供各工具复用）。
     */    // ════════════════════════════════════════════════════════════════════════
    // [H5-GAP-1] Session hook 匹配 · 对齐 CC getMatchingHooks matcher 过滤 (hooks.ts:1684)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 提取事件 matchQuery · 暴露 {@link #extractMatchQuery} 给 {@link HookRegistry} session hook
     * 执行链路 (CC getMatchingHooks hooks.ts:1615-1670 的 matchQuery 用于过滤 matcher + 供
     * getSessionHookCallback 定位 matcher 条目).
     *
     * <p>WHY (H5-GAP-1): session hooks (command/function) 由 {@link HookRegistry#executeEvent}
     * 按本引擎同款 matchQuery 语义过滤 — 与 settings 持久化 hooks 走同一匹配规则, 保证两类
     * hook 在 CC getAllHooks (hooksSettings.ts:146-158) 合并后行为一致.
     *
     * @param event hook 事件
     * @return matchQuery (工具事件=toolName; 无匹配字段事件=null)
     */
    public String matchQueryFor(HookEvent event) {
        return extractMatchQuery(event);
    }

    /**
     * 插件 hook matcher 判定 · 等价 CC getMatchingHooks matcher 过滤 (hooks.ts:1683-1686).
     *
     * <p><b>语义</b> (CC :1684): {@code matchQuery == null ? 全部通过 : matchesPattern} —
     * 事件无法提取匹配 key (Stop/UserPromptSubmit/TeammateIdle/TaskCreated/TaskCompleted/
     * WorktreeCreate/WorktreeRemove/CwdChanged) 时 matcher 过滤不生效, 所有 matcher 组保留,
     * 防过度过滤 (无法匹配的事件不存在"错误执行", 只有"漏执行").
     *
     * <p>供 {@code PluginLoader.buildPluginGenericHook} 对插件 hooks 组 (CC PluginHookMatcher,
     * loadPluginHooks.ts:74-81) 执行前过滤 — 与 settings/session hooks 同一匹配规则
     * ({@link #matchesPattern} 等价 CC :1346-1381).
     *
     * <p><b>null 解析器退化</b>: {@code permissionRuleValueParser} 为 null (测试中手动 new 场景)
     * 时 {@link #matchesPattern} 无法 normalize, 退化为精确字符串比较 — 空 matcher 匹配一切,
     * {@code *} 匹配一切, 否则 {@code matcher.equals(matchQuery)}. 生产路径 (Spring 注入) 恒走
     * 完整 matchesPattern (含 legacy 别名 + 正则).
     *
     * @param event   hook 事件
     * @param matcher 插件 hook 组的 matcher (CC loadPluginHooks.ts:74-81; null/空=匹配一切)
     * @return true = 该组插件 hooks 应在本事件执行
     */
    public boolean matchesMatcher(HookEvent event, String matcher) {
        String matchQuery = extractMatchQuery(event);
        if (matchQuery == null) {
            // CC :1684 matchQuery==null → 全部 matcher 通过 (无匹配字段事件如 Stop/UserPromptSubmit)
            return true;
        }
        if (permissionRuleValueParser == null) {
            return matcher == null || matcher.isEmpty() || "*".equals(matcher)
                    || matcher.equals(matchQuery);
        }
        return matchesPattern(matchQuery, matcher);
    }

    /**
     * 判断 session hook matcher 是否匹配事件 · 等价 CC getMatchingHooks matcher 过滤
     * (hooks.ts:1684 {@code matchQuery == null ? all : matchesPattern}).
     *
     * <p><b>实现</b>: 委托 {@link #matchesMatcher(HookEvent, String)} — 语义完全一致
     * (session hook 与插件 hook 走同一 matcher 过滤规则), 仅保留原公开 API 入口.
     *
     * @param event   hook 事件
     * @param matcher session hook 的 matcher (工具名匹配模式; null/空=匹配一切)
     * @return true = 该 session hook 应在本事件执行
     */
    public boolean matchesSessionMatcher(HookEvent event, String matcher) {
        return matchesMatcher(event, matcher);
    }
}
