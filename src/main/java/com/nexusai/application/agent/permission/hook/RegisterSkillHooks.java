package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Register Skill Hooks · 对齐 CC utils/hooks/registerSkillHooks.ts (64 行).
 *
 * <p><b>[Session H12] 真实实现</b>: 旧实现是空壳 ({@code registerForSkill} 恒返回 0).
 * 现对齐 CC registerSkillHooks.ts:20-64:
 * <ul>
 *   <li>遍历 HOOK_EVENTS → matchers → hooks (L29-57)</li>
 *   <li><b>once:true one-shot</b> (L36-43): 注册 onHookSuccess 回调, hook 首次成功执行后
 *       自动 removeSessionHook</li>
 *   <li><b>skillRoot 透传</b> (L52 + hooks.ts:890/908): skill 根目录透传到底层 matcher,
 *       供 hook 执行时注入 {@code CLAUDE_PLUGIN_ROOT} env</li>
 *   <li>注册数 &gt;0 时 logDebug (L59-63)</li>
 * </ul>
 *
 * <p><b>CLAUDE_PLUGIN_ROOT env</b> (CC hooks.ts:887-908): skill hook 执行时注入
 * {@code CLAUDE_PLUGIN_ROOT=<skillRoot>} — 与 plugin hook 共用同名 env 便于 skill 迁移到
 * plugin. Java 端执行阶段 (CommandHookExecutor) 需把 matcher.skillRoot 注入该 env; 本类
 * 负责把 skillRoot 存进 session hook matcher (对齐 CC addSessionHook skillRoot 参数).
 *
 * @see SessionHookStore#addSessionHook(String, HookEventType, String, HookCommand, SessionHookStore.OnHookSuccess, String)
 * @since Session H12
 */
@Component
public class RegisterSkillHooks {

    private static final Logger log = LoggerFactory.getLogger(RegisterSkillHooks.class);

    // [X25 删除] CLAUDE_PLUGIN_ROOT 常量已删除（2026-08-04 收尾）：全仓 0 引用
    //   （CommandHookExecutor.java:773/:872/:879 用字符串字面量 "CLAUDE_PLUGIN_ROOT"，
    //   不消费本常量）—— 零引用死常量不达 ⊕ 保留标准。

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HookRegistry hookRegistry;

    public RegisterSkillHooks(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * Skill hook matcher · 对齐 CC registerSkillHooks.ts 的 {@code matcher.matcher || ''}
     * 与 {@code matcher.hooks} 遍历结构 (L33-56).
     *
     * @param matcher CC original: matcher.matcher — 工具名匹配模式
     * @param hooks   CC original: matcher.hooks — HookCommand 列表
     */
    public record SkillHookMatcher(String matcher, List<HookCommand> hooks) {
        public SkillHookMatcher {
            hooks = hooks == null ? List.of() : hooks;
        }
    }

    /**
     * Skill hooks settings · 对齐 CC HooksSettings {@code Partial<Record<HookEvent, HookMatcher[]>>}
     * (registerSkillHooks.ts 遍历的 hooks 结构).
     *
     * @param byEvent event → SkillHookMatcher[] 映射
     */
    public record SkillHooksSettings(Map<HookEventType, List<SkillHookMatcher>> byEvent) {
        public SkillHooksSettings {
            byEvent = byEvent == null ? Map.of() : byEvent;
        }
    }

    /**
     * 注册 skill 的 frontmatter hooks 为 session hooks · 对齐 CC registerSkillHooks.ts:20-64.
     *
     * <p><b>once:true one-shot 语义</b> (CC L36-43): once hook 注册 onHookSuccess 回调,
     * 首次成功执行后自动 removeSessionHook — 防止每次 turn 重复触发 skill 副作用.
     *
     * @param sessionId CC original: sessionId — 会话 ID
     * @param hooks     CC original: hooks — HooksSettings (event → matcher → hooks)
     * @param skillName CC original: skillName — 仅用于日志
     * @param skillRoot CC original: skillRoot — skill 根目录 (CLAUDE_PLUGIN_ROOT env), 可 null
     * @return 注册的 hook 总数
     */
    public int registerForSkill(String sessionId, SkillHooksSettings hooks, String skillName, String skillRoot) {
        int registeredCount = 0;
        for (HookEventType event : HookEventType.values()) {
            List<SkillHookMatcher> matchers = hooks.byEvent().get(event);
            if (matchers == null || matchers.isEmpty()) {
                continue;
            }
            for (SkillHookMatcher matcher : matchers) {
                for (HookCommand hook : matcher.hooks()) {
                    // CC L36-43: once:true → onHookSuccess 回调, 首次成功后自动移除
                    SessionHookStore.OnHookSuccess onHookSuccess = Boolean.TRUE.equals(hook.once())
                            ? (h, result) -> {
                                if (log.isDebugEnabled()) {
                                    log.debug("移除 skill '{}' 的一次性 hook: event={} type={}",
                                            skillName, event, h.type());
                                }
                                hookRegistry.removeSessionHook(sessionId, event, (HookCommand) h);
                            }
                            : null;
                    hookRegistry.addSessionHook(
                            sessionId, event,
                            matcher.matcher() == null ? "" : matcher.matcher(),
                            hook,
                            onHookSuccess,
                            skillRoot);
                    registeredCount++;
                }
            }
        }
        if (registeredCount > 0) {
            if (log.isDebugEnabled()) {
                log.debug("已从 skill '{}' 注册 {} 个 hooks", skillName, registeredCount);
            }
        }
        return registeredCount;
    }

    /**
     * 从 {@link Command#getHooks()} (HooksSettings JSON) 解析 skill hooks 并注册 ·
     * <b>生产接线入口</b> (对齐 CC processSlashCommand.tsx:877 {@code registerSkillHooks(...)
     * — 加载 skill 时注册 frontmatter hooks)。
     *
     * <p><b>[Session H12 v2 对抗核验 Gap1 修复]</b>: 此前本方法生产零调用方
     * (registerForSkill 仅在测试中触发), skill 前端 once/skillRoot hooks 运行时永不注册.
     * 现由 {@link com.nexusai.application.agent.tool.impl.SkillToolImpl} 在技能执行时调用.
     *
     * @param sessionId CC original: sessionId — 会话 ID
     * @param skill     CC original: command — 待注册 hooks 的技能 (hooks JSON + name + skillRoot)
     * @return 注册的 hook 总数 (0 = 无 hooks 或解析失败)
     */
    public int registerSkillHooks(String sessionId, Command skill) {
        if (sessionId == null || skill == null) {
            return 0;
        }
        String hooksJson = skill.getHooks();
        if (hooksJson == null || hooksJson.isBlank()) {
            return 0;
        }
        SkillHooksSettings settings = fromHooksJson(hooksJson);
        if (settings.byEvent().isEmpty()) {
            return 0;
        }
        // [ALIGN-HS-1 △-SH-03] skillRoot 仅 prompt 型守卫 · CC original: processSlashCommand.tsx:877
        //   {@code command.type === 'prompt' ? command.skillRoot : undefined} — 仅 prompt 型命令
        //   传 skillRoot（CLAUDE_PLUGIN_ROOT env 来源）；命令型 Command baseDir 理论 null，但显式按
        //   type 守卫（对齐 CC），防止未来命令型载入非 null baseDir 时误注入 CLAUDE_PLUGIN_ROOT。
        return registerForSkill(sessionId, settings, skill.getName(),
                "prompt".equals(skill.getType()) ? skill.getBaseDir() : null);
    }

    /**
     * HooksSettings JSON → {@link SkillHooksSettings} · 对齐 CC registerSkillHooks.ts:29-57
     * 遍历的 {@code HooksSettings} 结构 ({@code { PreToolUse: [{matcher, hooks:[...]}], ... }}).
     *
     * <p>hook 数组按 CC discriminatedUnion 由 Jackson {@code @JsonSubTypes} 按 {@code type}
     * 字段路由到 {@link CommandHook}/{@link PromptHook}/{@link HttpHook}/{@link AgentHook}.
     * 非法 JSON / 未知事件名 / 非对象 hook → 跳过 (log warn), 不抛异常 (fail-loud 到日志).
     *
     * @param hooksJson CC original: HooksSettings JSON 字符串
     * @return 解析后的 settings (空 map = 无 hooks / 解析失败)
     */
    public static SkillHooksSettings fromHooksJson(String hooksJson) {
        if (hooksJson == null || hooksJson.isBlank()) {
            return new SkillHooksSettings(Map.of());
        }
        try {
            Map<String, Object> raw = JSON.readValue(hooksJson, new TypeReference<Map<String, Object>>() {});
            Map<HookEventType, List<SkillHookMatcher>> byEvent = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                HookEventType event = HookEventType.fromCcName(e.getKey());
                if (event == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("未知 CC hook 事件名 '{}', 跳过", e.getKey());
                    }
                    continue;
                }
                List<SkillHookMatcher> matchers = new ArrayList<>();
                if (e.getValue() instanceof List<?> rawMatchers) {
                    for (Object m : rawMatchers) {
                        if (m instanceof Map<?, ?> mm) {
                            matchers.add(parseMatcher(mm));
                        }
                    }
                }
                if (!matchers.isEmpty()) {
                    byEvent.put(event, matchers);
                }
            }
            return new SkillHooksSettings(byEvent);
        } catch (Exception ex) {
            // 非 JSON (如 SKILL.md YAML frontmatter 直出的 Map.toString()) → warn + 空, 不伪造注册
            log.warn("解析 skill hooks JSON 失败, 跳过注册: {}", ex.getMessage());
            return new SkillHooksSettings(Map.of());
        }
    }

    /** 单个 matcher 解析 · CC original: {@code { matcher, hooks: HookCommand[] }}. */
    private static SkillHookMatcher parseMatcher(Map<?, ?> mm) {
        String matcher = null;
        if (mm.get("matcher") != null) {
            matcher = mm.get("matcher").toString();
        }
        List<HookCommand> hooks = new ArrayList<>();
        Object rawHooks = mm.get("hooks");
        if (rawHooks instanceof List<?> rawList) {
            for (Object h : rawList) {
                if (h instanceof Map<?, ?> hm) {
                    try {
                        hooks.add(JSON.convertValue(hm, HookCommand.class));
                    } catch (Exception ex) {
                        log.warn("解析 skill hook 项失败, 跳过: {}", ex.getMessage());
                    }
                }
            }
        }
        return new SkillHookMatcher(matcher, hooks);
    }
}
