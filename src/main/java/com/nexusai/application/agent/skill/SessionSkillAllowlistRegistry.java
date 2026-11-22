package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级技能白名单 · 对齐 CC {@code sessionSkillAllowlist}
 * （exe v2.1.325 {@code DX()} = {@code host.extensionsConfig.sessionSkillAllowlist()}；
 * 写入侧 = SDK initialize 请求的 {@code options.skills}，非 agent frontmatter）。
 *
 * <p><b>语义（逐条对齐 CC）</b>：
 * <ul>
 *   <li><b>未配置</b>（本表无 key）→ 不过滤，通告全量（聊天默认态，与 CC 交互式一致）；</li>
 *   <li><b>{@code []}</b> → 过滤后为空 ⇒ <b>一条都不通告</b>（CC {@code Tve} 返回空列表）；</li>
 *   <li><b>{@code ["a","b"]}</b> → 只保留匹配项。</li>
 * </ul>
 *
 * <p><b>匹配规则</b>（CC exe {@code Tve}）：{@code name === s}，或
 * {@code name.endsWith(":" + s)}（插件命名空间短名）；MCP 技能不参与后缀匹配
 * （CC {@code tjn} 排除 {@code source==='mcp'}）。CC 的 aliases 分支只对
 * {@code loadedFrom==='syncedSkills'} 生效，本仓无该来源 ⇒ N/A。
 *
 * <p><b>只影响通告，不影响</b>：① 预加载（agent frontmatter {@code skills:}）；
 * ② Skill 工具仍可调用名单外技能。
 *
 * <p><b>作用范围</b>（CC {@code H$} / dynamic 分支）：
 * <ul>
 *   <li>skill_listing：主线程（{@code agentId == null}）、后台化主会话（{@code subagentName="main-session"}）、
 *       fork 子代理（{@code subagentName="fork" && isBuiltIn}）；普通子代理不过滤；</li>
 *   <li>dynamic_skill 附件：仅 {@code agentId == null}（CC {@code e.agentId===void 0}，fork 也不过滤）。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：进程级内存表（同 CC 的 host extensionsConfig，不落库）；重启即失效，
 * 需 SDK 侧重新初始化。与 {@link SkillListingSentRegistry} 一样“多会话共享 JVM”必须带 sessionId 键。
 */
public final class SessionSkillAllowlistRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionSkillAllowlistRegistry.class);

    /** sessionId → 白名单。null value 不入表（未配置）；空列表是合法的「全禁」配置。 */
    private static final ConcurrentHashMap<String, List<String>> ALLOWLISTS = new ConcurrentHashMap<>();

    private SessionSkillAllowlistRegistry() {
    }

    /**
     * 设置/清除会话白名单。
     *
     * @param sessionId 会话 id（null/空 → no-op）
     * @param skills    白名单；<b>null = 清除（恢复不过滤）</b>，空列表 = 一条都不通告
     */
    public static void put(String sessionId, List<String> skills) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (skills == null) {
            ALLOWLISTS.remove(sessionId);
            log.info("[SessionSkillAllowlist] session={} 清除白名单（恢复全量通告）", sessionId);
            return;
        }
        List<String> copy = List.copyOf(skills);
        ALLOWLISTS.put(sessionId, copy);
        log.info("[SessionSkillAllowlist] session={} 设置白名单 size={} skills={}",
            sessionId, copy.size(), copy);
    }

    /**
     * 从「人格（agent frontmatter）的 skills」派生白名单。
     *
     * <p><b>与手工白名单的区别</b>：人格没点名技能时（MD 解析层无法区分「缺省」与
     * {@code skills: []}，两者都是空列表）→ <b>清除白名单（不过滤）</b>，而不是映射成
     * 「全禁」——否则任何没写 skills 的人格都会让模型一个技能都看不到。
     * 这是本仓扩展（CC 的 sessionSkillAllowlist 不读 agent md）。
     *
     * @param agentSkills 人格声明的 skills（null/空 → 清除）
     */
    public static void putFromAgent(String sessionId, List<String> agentSkills) {
        put(sessionId, (agentSkills == null || agentSkills.isEmpty()) ? null : agentSkills);
    }

    /**
     * @return 白名单；<b>null = 未配置（不过滤）</b>，空列表 = 全禁
     */
    public static List<String> get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return ALLOWLISTS.get(sessionId);
    }

    public static void clear(String sessionId) {
        if (sessionId != null) {
            ALLOWLISTS.remove(sessionId);
        }
    }

    /**
     * skill_listing 是否套用白名单 · 对齐 CC {@code H$(agentContext)}。
     *
     * @param agentId      主线程为 null（CC {@code agentType==="main"}）
     * @param subagentName 子代理身份名（CC {@code subagentName}）
     * @param isBuiltIn    是否内置子代理（fork 判定需要）
     */
    public static boolean appliesToListing(String agentId, String subagentName, boolean isBuiltIn) {
        if (agentId == null) {
            return true;
        }
        if (subagentName == null) {
            return false;
        }
        // 后台化主会话：SubagentContext{agentId=taskId, subagentName="main-session"}（RunRequest:126）
        if ("main-session".equals(subagentName)) {
            return true;
        }
        // CC：agentType==="subagent" && isMainSession || forkOfMainConversation
        return "fork".equals(subagentName) && isBuiltIn;
    }

    /** dynamic_skill 附件是否套用白名单 · 对齐 CC {@code e.agentId===void 0}（仅主线程）。 */
    public static boolean appliesToDynamicSkill(String agentId) {
        return agentId == null;
    }

    /**
     * 过滤通告命令列表 · 对齐 CC {@code Tve(list, allowlist)}。
     *
     * @param commands  候选命令（可为 null）
     * @param allowlist 白名单；null = 不过滤原样返回；空列表 = 返回空
     */
    public static List<Command> filterByAllowlist(List<Command> commands, List<String> allowlist) {
        if (allowlist == null || commands == null) {
            return commands;
        }
        return commands.stream()
            .filter(cmd -> cmd != null && matchesName(cmd.getName(), isMcp(cmd), allowlist))
            .toList();
    }

    /**
     * 过滤 dynamic_skill 的直接技能名（字符串列表）· 同一套 name/后缀规则。
     *
     * @param names     技能名（可为 null）
     * @param allowlist 白名单；null = 不过滤
     */
    public static List<String> filterNamesByAllowlist(List<String> names, List<String> allowlist) {
        if (allowlist == null || names == null) {
            return names;
        }
        return names.stream()
            .filter(name -> name != null && matchesName(name, false, allowlist))
            .toList();
    }

    /** CC {@code Tve} 匹配：name 精确，或（非 MCP）以 {@code ":"+s} 结尾。 */
    static boolean matchesName(String name, boolean isMcp, List<String> allowlist) {
        if (name == null) {
            return false;
        }
        for (String s : allowlist) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (name.equals(s)) {
                return true;
            }
            if (!isMcp && name.endsWith(":" + s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMcp(Command cmd) {
        return cmd.getSource() == CommandSource.MCP
            || Boolean.TRUE.equals(cmd.getIsMcp())
            || cmd.getLoadedFrom() == com.nexusai.model.command.CommandLoadedFrom.MCP;
    }
}
