package com.nexusai.application.agent.skillsearch;

import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * skill-search 子系统 remote canonical 技能执行骨架 · 对齐 CC {@code SkillTool.ts:969-1108 executeRemoteSkill}
 * + {@code remoteSkillState.ts}（stripCanonicalPrefix/getDiscoveredRemoteSkill，<b>CC 真源已确认存在</b>）。
 *
 * <p><b>Java 未接线 CC 既有实现（prompt-align TOOLS-07 更新）</b>: 上游
 * {@code services/skillSearch/remoteSkillState.ts} 与 {@code services/skillSearch/remoteSkillLoader.ts}
 * 真源均存在（本 checkout 已 ls 复验），Java 侧未接线。本类为「骨架 + todo」，<b>本期不实施、不接线</b>
 * （P1-7 拍板：executeRemoteSkill → 建骨架 + 写 todo，接线待 Java remote 执行路径落地后对接）。
 *
 * <p><b>Java 当前无 remote execution</b>（全仓 grep 0 命中 executeRemoteSkill）：CC remote canonical 路径
 * 全部 gate 于 {@code feature('EXPERIMENTAL_SKILL_SEARCH') && process.env.USER_TYPE==='ant'}（ant-only 实验特性），
 * Java web 部署非 ant + feature 默认关 → 不实现为可观测行为，仅登记契约骨架。
 *
 * <p><b>CC remote canonical 全链契约（四接入点，均须在接线时实现）</b>：
 * <ol>
 *   <li>validateInput（SkillTool.ts:377-396）：{@code stripCanonicalPrefix(normalizedCommandName)} 非 null →
 *       {@code getDiscoveredRemoteSkill(slug)} 无 meta → errorCode 6「Remote skill X was not discovered...」；
 *       有 meta → pass（本地命令查找前拦截）。</li>
 *   <li>checkPermissions（SkillTool.ts:488-504）：deny 循环之后、allow 循环之前，canonical slug 命中 →
 *       auto-grant Allow（decisionReason undefined；内容 canonical/curated 非用户自述）。</li>
 *   <li>call 路由（SkillTool.ts:605-613）：本地命令查找前拦截 canonical slug → {@link #executeRemoteSkill}。</li>
 *   <li>executeRemoteSkill（SkillTool.ts:969-1108）：全管线（见 {@link #executeRemoteSkill} javadoc）。</li>
 * </ol>
 *
 * <p><b>已存在的配套骨架</b>（C-30，各独立文件）：{@link SkillSearchRemoteLoader}（loadRemoteSkill/
 * logRemoteSkillLoaded）、{@link SkillSearchRemoteState}（会话状态）、{@link SkillSearchTelemetry}、
 * {@link SkillSearchFeatureCheck}（isSkillSearchEnabled）。本类承担 SkillTool.ts 侧的 remote 执行编排契约。
 */
public final class SkillSearchRemoteExecution {

    private static final Logger log = LoggerFactory.getLogger(SkillSearchRemoteExecution.class);

    /**
     * canonical 前缀 · CC 注释原文引用（SkillTool.ts:375/489/601）{@code `_canonical_<slug>`}。
     *
     * <p><b>注意</b>: CC 真源 {@code remoteSkillState.ts stripCanonicalPrefix} 已存在（Java 未接线），
     * 本常量据 CC 注释推断，待 Java 接线后对照真源核对（勿假定 CC 契约）。
     */
    public static final String CANONICAL_PREFIX = "_canonical_";

    private SkillSearchRemoteExecution() {
        // 纯静态工具类 · 禁止实例化（CC 模块级函数）
    }

    /**
     * CC original: {@code stripCanonicalPrefix}（remoteSkillState.ts，CC 真源已存在）· SkillTool.ts:381/:606
     * 在本地命令查找前把 {@code _canonical_<slug>} 名还原为 slug。
     *
     * <p>契约（据 CC 注释推断）：{@code normalizedCommandName} 以 {@code _canonical_} 开头 → 返回去前缀的
     * slug（空 slug 视为不命中 → null）；否则 null。CC 用 null 判定「非 remote canonical」（SkillTool.ts:383
     * {@code if (slug !== null)}）。
     *
     * @param normalizedCommandName 已剥前导斜杠的命令名（CC normalizedCommandName）
     * @return slug；非 canonical 名 → null
     */
    public static String stripCanonicalPrefix(String normalizedCommandName) {
        if (normalizedCommandName == null) {
            return null;
        }
        if (!normalizedCommandName.startsWith(CANONICAL_PREFIX)) {
            return null;
        }
        String slug = normalizedCommandName.substring(CANONICAL_PREFIX.length());
        return slug.isEmpty() ? null : slug;
    }

    /**
     * CC original: {@code getDiscoveredRemoteSkill}（remoteSkillState.ts，CC 真源已存在）· SkillTool.ts:385/:990
     * 取会话内 DiscoverSkills 发现的 remote skill 元数据（含 URL）。
     *
     * <p><b>Java 无 session remote skill 状态存储</b>（SkillSearchRemoteState 骨架未接线）→ 恒 null
     * （对齐 CC validateInput :386 {@code if (!meta)} 分支 → errorCode 6 fail）。
     *
     * @param slug 去前缀的 remote skill slug
     * @return meta；未发现 → null（当前恒 null）
     */
    public static RemoteSkillMeta getDiscoveredRemoteSkill(String slug) {
        if (log.isDebugEnabled()) {
            log.debug("[SkillSearchRemoteExecution] getDiscoveredRemoteSkill 占位 null · CC 真源 remoteSkillState.ts "
                    + "已存在（Java 未接线），Java 无 session remote skill 状态（P1-7 骨架，未接线）slug={}", slug);
        }
        return null;
    }

    /**
     * CC original: {@code executeRemoteSkill}（SkillTool.ts:969-1108）· remote canonical 技能执行全管线。
     *
     * <p><b>本期不实施</b>（P1-7）：骨架抛 {@link UnsupportedOperationException} fail loud —— 防止未来接线
     * 者误以为已实现而静默跳过（CLAUDE.md 规则十二）。接线时按下方 CC 全管线逐步实现：
     * <ol>
     *   <li><b>re-fetch meta</b>（:978-986）：{@code getDiscoveredRemoteSkill(slug)} 无 meta →
     *       throw Error("Remote skill ${slug} was not discovered in this session. Use DiscoverSkills to find remote skills first.")</li>
     *   <li><b>urlScheme</b>（:988）：{@code extractUrlScheme(meta.url)}（gs/http/https/s3，SkillTool.ts:949-955）</li>
     *   <li><b>load</b>（:990-1004）：{@code loadRemoteSkill(slug, meta.url)}（SKILL_SEARCH_LOADER 骨架）；失败 →
     *       {@code logRemoteSkillLoaded({slug, cacheHit:false, latencyMs:0, urlScheme, error})} + throw Error("Failed to load remote skill ${slug}: ${msg}")</li>
     *   <li><b>load 成功遥测</b>（:1006-1015）：{@code logRemoteSkillLoaded({slug, cacheHit, latencyMs, urlScheme, fileCount, totalBytes, fetchMethod})}</li>
     *   <li><b>tengu_skill_tool_invocation</b>（:1017-1057）：command_name='remote_skill'、
     *       _PROTO_skill_name=commandName、execution_context='remote'、invocation_trigger（query_depth>0?
     *       'nested-skill':'claude-proactive'）、query_depth、parent_agent_id（有则发）、was_discovered=true、
     *       is_remote=true、remote_cache_hit=cacheHit、remote_load_latency_ms=latencyMs、ant-only skill_name/remote_slug</li>
     *   <li><b>recordSkillUsage(commandName)</b>（:1059）：复用 SkillToolImpl recordSkillUsage 槽位</li>
     *   <li><b>parseFrontmatter</b>（:1064-1066）：剥 YAML frontmatter（对齐 loadSkillsDir.ts:333），无 frontmatter 原样返回</li>
     *   <li><b>目录头 + 替换</b>（:1068-1078）：{@code `Base directory for this skill: ${normalizedDir}\n\n${bodyContent}`}
     *       + {@code ${CLAUDE_SKILL_DIR}}/${CLAUDE_SESSION_ID} 替换（win32 反斜杠转正斜杠）</li>
     *   <li><b>addInvokedSkill</b>（:1080-1087）：{@code addInvokedSkill(commandName, skillPath, finalContent, agentId??null)}
     *       （AgentState.addInvokedSkill 写侧已实现，AgentState.java:856-871）</li>
     *   <li><b>返回</b>（:1089-1107）：{@code {data:{success:true, commandName, status:'inline'}}}
     *       + {@code tagMessagesWithToolUseID([createUserMessage({content: finalContent, isMeta:true})], toolUseID)}
     *       （复用 SkillToolImpl.tagMessagesWithToolUseID 槽位）</li>
     * </ol>
     *
     * @param slug        去前缀 remote skill slug
     * @param commandName 完整命令名（遥测 _PROTO_skill_name 用）
     * @param parentBlock 父消息 Skill tool_use 块（toolUseID 关联，CC parentMessage.message.id）
     * @param context     工具调用上下文
     * @return 不返回（本期骨架抛异常）；接线后返回 inline ToolResult（data+newMessages）
     */
    public static AgentToolResult executeRemoteSkill(String slug, String commandName,
                                                     ToolUseBlock parentBlock, ToolUseContext context) {
        throw new UnsupportedOperationException(
                "[P1-7 骨架未接线] executeRemoteSkill 待上游 services/skillSearch/* 源码 + Java remote 执行路径落地后实现 "
                        + "（CC SkillTool.ts:969-1108 全管线契约见 javadoc）；slug=" + slug + " commandName=" + commandName);
    }

    /**
     * remote skill 元数据 · CC original: {@code DiscoveredRemoteSkill}（remoteSkillState.ts，CC 真源已存在）。
     *
     * <p>契约（据 CC executeRemoteSkill 消费点推断）：{@code {url}} 为必填（SkillTool.ts:988
     * {@code extractUrlScheme(meta.url)}），DiscoverSkills 发现时写入会话状态。本骨架仅占位。
     *
     * @param url 远端 SKILL.md 加载地址（AKI/GCS）
     */
    public record RemoteSkillMeta(String url) {
    }
}
