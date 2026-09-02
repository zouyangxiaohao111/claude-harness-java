package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SkillToolPrompt · 移植 CC {@code tools/SkillTool/prompt.ts}（独立模块）。
 *
 * <p>职责：生成 Skill 工具的 LLM 使用指南（{@link #getPrompt()}）+ 技能统计信息
 * （{@link #getSkillToolInfo} / {@link #getSkillInfo} / {@link #getLimitedSkillToolCommands}）
 * + 缓存失效（{@link #clearPromptCache()}），对齐 CC {@code SkillTool.ts:344}
 * {@code prompt: async () => getPrompt(getProjectRoot())} 的数据源。
 *
 * <p>CC 真源结构（行号已 Read 实证）:
 * <ul>
 *   <li>{@code getPrompt = memoize(async (cwd) => {...})} (prompt.ts:173) → {@link #getPrompt()}
 *       （CC 按 cwd memoize，文本静态 → Java 单值缓存，语义等价）</li>
 *   <li>{@code getSkillToolInfo} (prompt.ts:198-208) → {@link #getSkillToolInfo}；
 *       {@code totalCommands === includedCommands === getSkillToolCommands(cwd).length}</li>
 *   <li>{@code getLimitedSkillToolCommands} (prompt.ts:213-215) → {@link #getLimitedSkillToolCommands}
 *       （直接返回 {@code getSkillToolCommands(cwd)}；P2-18 analyzeContext skill token 计数上游依赖）</li>
 *   <li>{@code clearPromptCache} (prompt.ts:217-219) → {@link #clearPromptCache()}
 *       （{@code getPrompt.cache?.clear?.()}）</li>
 *   <li>{@code getSkillInfo} (prompt.ts:221-241) → {@link #getSkillInfo}
 *       （用 {@code getSlashCommandToolSkills}，try/catch → {totalSkills:0, includedSkills:0}）</li>
 * </ul>
 *
 * <p>数据源映射：{@code getSkillToolCommands(cwd)}（commands.ts:563）→ Java
 * {@link SkillRegistry#getModelInvocableCommands()}（P1-1 已 memoize）；{@code getSlashCommandToolSkills}
 * （commands.ts:586）→ {@link SkillRegistry#getSlashCommandToolSkills()}（本项 P2-3 新增）。
 */
public final class SkillToolPrompt {

    private static final Logger log = LoggerFactory.getLogger(SkillToolPrompt.class);

    /**
     * 静态工具指南文本 · 对齐 CC {@code prompt.ts:174-195} 模板字面量（逐字一致）。
     *
     * <p>与旧 Java 内联文本（SkillToolImpl.prompt() 旧 body）差异：
     * ① 示例区补全 {@code ms-office-suite:pdf} 全限定名行（prompt.ts:186）；
     * ② 尾部换行（CC 模板字面量以换行收尾）。{@code <${COMMAND_NAME_TAG}>} → {@code <command-name>}
     * （COMMAND_NAME_TAG = 'command-name'，src/constants/xml.ts:2）。
     */
    private static final String PROMPT_TEXT = """
            Execute a skill within the main conversation

            When users ask you to perform tasks, check if any of the available skills match. Skills provide specialized capabilities and domain knowledge.

            When users reference a "slash command" or "/<something>" (e.g., "/commit", "/review-pr"), they are referring to a skill. Use this tool to invoke it.

            How to invoke:
            - Use this tool with the skill name and optional arguments
            - Examples:
              - `skill: "pdf"` - invoke the pdf skill
              - `skill: "commit", args: "-m 'Fix bug'"` - invoke with arguments
              - `skill: "review-pr", args: "123"` - invoke with arguments
              - `skill: "ms-office-suite:pdf"` - invoke using fully qualified name

            Important:
            - Available skills are listed in system-reminder messages in the conversation
            - When a skill matches the user's request, this is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response about the task
            - NEVER mention a skill without actually calling this tool
            - Do not invoke a skill that is already running
            - Do not use this tool for built-in CLI commands (like /help, /clear, etc.)
            - If you see a <command-name> tag in the current conversation turn, the skill has ALREADY been loaded - follow the instructions directly instead of calling this tool again
            """;

    /**
     * getPrompt memoize 单值缓存 · 对齐 CC {@code getPrompt = memoize(async (cwd) => {...})}
     * （prompt.ts:173）。CC 按 cwd 缓存但文本静态 → Java 用进程生命周期单值（lazy volatile），
     * 语义等价（重复调用返回同引用）。唯一失效入口 {@link #clearPromptCache()}。
     */
    private static volatile String cachedPrompt;

    private SkillToolPrompt() {
    }

    /**
     * 生成 Skill 工具使用指南 · 对齐 CC {@code prompt.ts:173-196 getPrompt}。
     *
     * <p>memoize 语义：首次调用构建并缓存，后续返回同引用；{@link #clearPromptCache()} 后
     * 重建（对齐 CC lodash memoize 按 cwd 缓存 + caches.ts:141-142 / cacheUtils.ts:48 清理接线）。
     * 消费点：{@link SkillToolImpl#prompt()}（SkillTool.ts:344），经 ToolRegistry:424
     * {@code prompt() 非 null 优先于 description()} 注入 LLM 可见工具描述。
     *
     * @return 与 CC prompt.ts 逐字一致的静态文本（含 ms-office-suite:pdf 行 + 尾部换行）
     */
    public static String getPrompt() {
        String cached = cachedPrompt;
        if (cached == null) {
            cached = PROMPT_TEXT;
            cachedPrompt = cached;
            if (log.isDebugEnabled()) {
                log.debug("[SkillToolPrompt] getPrompt memoize 首次构建并缓存 (len={})", cached.length());
            }
        }
        return cached;
    }

    /**
     * 清空 prompt 缓存 · 对齐 CC {@code prompt.ts:217-219 clearPromptCache}
     * （{@code getPrompt.cache?.clear?.()}）。
     *
     * <p>CC 接线点：caches.ts:141-142（/clear 命令）+ cacheUtils.ts:48（插件缓存工具）；
     * Java 侧无 /clear 等价物，本方法为 P1-16 hot-reload / SkillChangeDetector 预留的失效入口
     * （未接线时 memoize 仅为常量优化，无观察差异）。
     */
    public static void clearPromptCache() {
        cachedPrompt = null;
        if (log.isDebugEnabled()) {
            log.debug("[SkillToolPrompt] clearPromptCache 已清空 prompt 缓存");
        }
    }

    /**
     * 技能工具统计信息 · 对齐 CC {@code prompt.ts:198-208 getSkillToolInfo}。
     *
     * <p>CC：{@code totalCommands === includedCommands === getSkillToolCommands(cwd).length}
     * （所有命令恒被包含，仅描述可能截断）；Java 数据源 =
     * {@link SkillRegistry#getModelInvocableCommands()}（对齐 CC getSkillToolCommands commands.ts:563）。
     *
     * @param registry 技能注册中心（getModelInvocableCommands 数据源）
     * @return totalCommands = includedCommands = 模型可调用命令数
     */
    public static SkillToolInfo getSkillToolInfo(SkillRegistry registry) {
        int count = registry.getModelInvocableCommands().size();
        if (log.isDebugEnabled()) {
            log.debug("[SkillToolPrompt] getSkillToolInfo: totalCommands=includedCommands={} (CC prompt.ts:198-208)",
                count);
        }
        return new SkillToolInfo(count, count);
    }

    /**
     * 有限技能工具命令清单 · 对齐 CC {@code prompt.ts:213-215 getLimitedSkillToolCommands}。
     *
     * <p>CC 直接返回 {@code getSkillToolCommands(cwd)}（所有命令恒被包含，仅描述可截断）；
     * P2-18 analyzeContext 用它统计 skill token 消耗。Java 数据源 =
     * {@link SkillRegistry#getModelInvocableCommands()}。
     *
     * @param registry 技能注册中心
     * @return 模型可调用命令清单（CC getSkillToolCommands 等价物）
     */
    public static List<Command> getLimitedSkillToolCommands(SkillRegistry registry) {
        List<Command> commands = registry.getModelInvocableCommands();
        if (log.isDebugEnabled()) {
            log.debug("[SkillToolPrompt] getLimitedSkillToolCommands: {} 个命令 (CC prompt.ts:213-215)",
                commands.size());
        }
        return commands;
    }

    /**
     * 技能统计信息 · 对齐 CC {@code prompt.ts:221-241 getSkillInfo}。
     *
     * <p>CC 用 {@code getSlashCommandToolSkills(cwd)}（第二套过滤 commands.ts:586-608），
     * {@code totalSkills === includedSkills === skills.length}；try/catch →
     * {totalSkills:0, includedSkills:0}（prompt.ts:232-240，加载失败不中断调用方）。
     *
     * <p>Java 数据源 = {@link SkillRegistry#getSlashCommandToolSkills()}（本项新增，对齐
     * commands.ts:586 第二套过滤）；SkillRegistry 每源 try-catch 隔离（loadAllCommands）使其恒不抛，
     * 本 catch 为防御性冗余（行为与 CC 等价）。
     *
     * @param registry 技能注册中心
     * @return totalSkills = includedSkills = 斜杠命令技能数；异常时 {0,0}
     */
    public static SkillInfo getSkillInfo(SkillRegistry registry) {
        try {
            int count = registry.getSlashCommandToolSkills().size();
            if (log.isDebugEnabled()) {
                log.debug("[SkillToolPrompt] getSkillInfo: totalSkills=includedSkills={} (CC prompt.ts:221-241)",
                    count);
            }
            return new SkillInfo(count, count);
        } catch (Exception e) {
            log.warn("[SkillToolPrompt] getSkillInfo 异常 → 返回 {0,0} (CC prompt.ts:232-240 防御兜底): {}",
                e.getMessage());
            return new SkillInfo(0, 0);
        }
    }

    /**
     * 技能工具统计信息 record · 对齐 CC {@code prompt.ts:198-208 getSkillToolInfo} 返回对象
     * （{@code {totalCommands, includedCommands}}）。
     */
    public record SkillToolInfo(int totalCommands, int includedCommands) {
    }

    /**
     * 技能统计信息 record · 对齐 CC {@code prompt.ts:221-241 getSkillInfo} 返回对象
     * （{@code {totalSkills, includedSkills}}）。
     */
    public record SkillInfo(int totalSkills, int includedSkills) {
    }
}
