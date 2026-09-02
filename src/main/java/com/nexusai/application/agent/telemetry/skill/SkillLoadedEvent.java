package com.nexusai.application.agent.telemetry.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能加载遥测 · 等价 CC utils/telemetry/skillLoadedEvent.ts:13-39 {@code logSkillsLoaded}（P2-11）。
 *
 * <p><b>CC 真源（E1/E2，Read skillLoadedEvent.ts:13-39）</b>：
 * <pre>
 * export async function logSkillsLoaded(cwd, contextWindowTokens) {
 *   const skills = await getSkillToolCommands(cwd)          // :22
 *   const skillBudget = getCharBudget(contextWindowTokens)  // :23
 *   for (const skill of skills) {                            // :25
 *     if (skill.type !== 'prompt') continue                  // :26
 *     logEvent('tengu_skill_loaded', {                       // :28-36
 *       _PROTO_skill_name: skill.name,                        // :30 未脱敏技能名，路由特权 BQ 列
 *       skill_source: skill.source,                           // :31
 *       skill_loaded_from: skill.loadedFrom,                  // :32
 *       skill_budget: skillBudget,                            // :33
 *       ...(skill.kind && { skill_kind: skill.kind }),        // :34-35 仅 truthy 发
 *     })
 *   }
 * }
 * </pre>
 *
 * <p><b>Java 等价物</b>：
 * <ul>
 *   <li>{@code getSkillToolCommands(cwd)} → {@code SkillRegistry.getModelInvocableCommands()}
 *       （P1-9，commands.ts:563-581 等价，纯本地模型可调用命令，MCP 在 getCommands 之外）</li>
 *   <li>{@code getCharBudget(contextWindowTokens)} → {@code SkillCatalog.getCharBudget(Integer)}
 *       （P2-19 动态预算）</li>
 *   <li>枚举 → CC camelCase 字符串：{@link #skillSourceCcValue}（P2-19 拆分后
 *       USER→userSettings / PROJECT_SETTINGS→projectSettings / POLICY_SETTINGS→policySettings …）
 *       与 {@code skillLoadedFromCcValue}（COMMANDS_DEPRECATED→commands_DEPRECATED 混合大小写）</li>
 * </ul>
 *
 * <p><b>接线点</b>：LlmAgentLoop A8 skill_listing 注入路径（isInitial 首帧 = 会话启动），
 * 每次会话启动对每个 prompt 技能发射一条 {@code tengu_skill_loaded}。
 */
public final class SkillLoadedEvent {

    private static final Logger log = LoggerFactory.getLogger(SkillLoadedEvent.class);

    private SkillLoadedEvent() {
        // 静态工具类
    }

    /**
     * 对每个 prompt 技能发射 {@code tengu_skill_loaded} 遥测事件 · 等价 CC
     * {@code logSkillsLoaded}（skillLoadedEvent.ts:28-36）。
     *
     * <p><b>null-safe</b>：telemetry 未注入（测试/降级）→ debug 日志跳过，不破坏执行链
     * （对齐 CC logEvent best-effort 语义）。
     *
     * @param telemetry  遥测适配层（可为 null → 跳过）
     * @param skills     模型可调用命令列表（CC getSkillToolCommands 产物；等价
     *                   {@code SkillRegistry.getModelInvocableCommands()}）
     * @param skillBudget 技能清单字符预算（CC getCharBudget 产物；等价
     *                   {@code SkillCatalog.getCharBudget(contextWindowTokens)}）
     */
    public static void logSkillsLoaded(Telemetry telemetry, List<Command> skills, int skillBudget) {
        if (telemetry == null) {
            return;
        }
        if (skills == null || skills.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillLoadedEvent] 无技能可加载，跳过 tengu_skill_loaded（CC skillLoadedEvent.ts:25 空列表）");
            }
            return;
        }
        int emitted = 0;
        for (Command skill : skills) {
            if (skill == null || !"prompt".equals(skill.getType())) {
                // CC :26 `if (skill.type !== 'prompt') continue` —— 非 prompt 型不发射
                continue;
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("_PROTO_skill_name", skill.getName());       // CC original: _PROTO_skill_name（:30）
            attrs.put("skill_source", skillSourceCcValue(skill.getSource())); // CC :31 — SettingSource camelCase
            attrs.put("skill_loaded_from", skillLoadedFromCcValue(skill.getLoadedFrom())); // CC :32 — commands_DEPRECATED 混合大小写
            attrs.put("skill_budget", skillBudget);                // CC :33
            // P3-29: CC :34-35 `...(skill.kind && {...})` = JS truthy —— null/undefined/""（falsy）不发，
            // 空白串 " "（truthy）仍发。Java 原 `!isBlank()` 会排除空白串，改为 `!isEmpty()` 精确对齐。
            if (skill.getKind() != null && !skill.getKind().isEmpty()) {
                attrs.put("skill_kind", skill.getKind());          // CC :34-35 仅 truthy 发
            }
            telemetry.recordEvent("tengu_skill_loaded", attrs);
            emitted++;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillLoadedEvent] tengu_skill_loaded 发射完成：{} 个技能 budget={}（CC skillLoadedEvent.ts:28-36）",
                emitted, skillBudget);
        }
    }

    /**
     * [ALIGN-HS-1 SU-△-2 / OQ-1 / P2-19] CommandSource → CC {@code skill_source} 字符串 · 对齐 CC
     * {@code PromptCommand.source}（types/command.ts:32 {@code SettingSource | 'builtin' | 'mcp' |
     * 'plugin' | 'bundled'}）与 {@code SETTING_SOURCES}（constants.ts:7-21 camelCase）。
     *
     * <p>P2-19 拆分：旧实现把 CC 5 个 SettingSource 变体（userSettings/projectSettings/
     * localSettings/flagSettings/policySettings）在模型层折叠为 {@code USER} → 遥测恒发
     * {@code "userSettings"}，项目/附加目录技能（CC source='projectSettings'）遥测桶塌缩
     * （EV-WF7-TU-015/016/017）。本映射按 {@link CommandSource} 细分值输出 CC 精确字符串：
     * {@code USER → "userSettings"}、{@code PROJECT_SETTINGS → "projectSettings"}、
     * {@code LOCAL_SETTINGS → "localSettings"}、{@code FLAG_SETTINGS → "flagSettings"}、
     * {@code POLICY_SETTINGS → "policySettings"}、其余（builtin/plugin/mcp/bundled）原样小写（CC 同值）。
     *
     * <p><b>复用方</b>：[ALIGN-HS-1 OQ-1] {@code ContextAnalyzeService.countSkillTokens()} 的
     * {@code SkillFrontmatterDetail.source} 复用本映射（analyzeContext.ts:591-593
     * {@code source: (skill.type === 'prompt' ? skill.source : 'plugin')}），P2-19 后 analyze
     * 输出 skillFrontmatter.source 与遥测 skill_source 同源细分（△-5 同源修复）。
     *
     * @param source Java CommandSource（null → null，等价 CC undefined）
     * @return CC skill_source 字符串（camelCase）
     */
    public static String skillSourceCcValue(com.nexusai.model.command.CommandSource source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case USER -> "userSettings";           // CC original: 'userSettings'（constants.ts:9）
            case PROJECT_SETTINGS -> "projectSettings"; // CC original: 'projectSettings'（constants.ts:12）
            case LOCAL_SETTINGS -> "localSettings";     // CC original: 'localSettings'（constants.ts:15）
            case FLAG_SETTINGS -> "flagSettings";       // CC original: 'flagSettings'（constants.ts:18）
            case POLICY_SETTINGS -> "policySettings"; // CC original: 'policySettings'（constants.ts:21）
            case BUILTIN -> "builtin";             // CC original: 'builtin'（types/command.ts:32 联合）
            case PLUGIN -> "plugin";               // CC original: 'plugin'
            case MCP -> "mcp";                     // CC original: 'mcp'
            case BUNDLED -> "bundled";             // CC original: 'bundled'
        };
    }

    /**
     * [ALIGN-HS-1 SU-△-2] CommandLoadedFrom → CC {@code skill_loaded_from} 字符串 · 对齐 CC
     * {@code LoadedFrom} type（loadSkillsDir.ts:67-74）与 {@code loadedFrom} 字段值。
     *
     * <p>旧实现 {@code name().toLowerCase()} 产出 {@code commands_deprecated}（全小写），与 CC
     * {@code 'commands_DEPRECATED'}（types/command.ts:192 / loadSkillsDir.ts:68 混合大小写）不一致 —
     * 遥测 {@code skill_loaded_from} 桶分裂。本映射按 CC 精确字符串逐一对应。
     *
     * @param loadedFrom Java CommandLoadedFrom（null → null，等价 CC undefined）
     * @return CC skill_loaded_from 字符串（commands_DEPRECATED 保留混合大小写）
     */
    private static String skillLoadedFromCcValue(com.nexusai.model.command.CommandLoadedFrom loadedFrom) {
        if (loadedFrom == null) {
            return null;
        }
        return switch (loadedFrom) {
            case COMMANDS_DEPRECATED -> "commands_DEPRECATED"; // CC original: 'commands_DEPRECATED'（loadSkillsDir.ts:68）
            case SKILLS -> "skills";               // CC original: 'skills'（loadSkillsDir.ts:69）
            case PLUGIN -> "plugin";               // CC original: 'plugin'（loadSkillsDir.ts:70）
            case MANAGED -> "managed";             // CC original: 'managed'（loadSkillsDir.ts:71）
            case BUNDLED -> "bundled";             // CC original: 'bundled'（loadSkillsDir.ts:72）
            case MCP -> "mcp";                     // CC original: 'mcp'（loadSkillsDir.ts:73）
        };
    }
}
