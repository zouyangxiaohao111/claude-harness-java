package com.nexusai.apis.session;

import com.nexusai.application.agent.skill.SessionSkillAllowlistRegistry;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话级技能白名单 · 对齐 CC SDK initialize 的 {@code options.skills}
 * （exe {@code sessionSkillAllowlist}；只影响 skill_listing 通告，不影响预加载 / Skill 工具调用）。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>不调用 / body 的 {@code skills=null} → 清除白名单 = 不过滤（聊天默认）；</li>
 *   <li>{@code skills=[]} → 一条都不通告；</li>
 *   <li>{@code skills=["a","b"]} → 只通告匹配项（name 或 {@code :后缀}）。</li>
 * </ul>
 *
 * <p><b>内存态</b>：与 CC 一致不落库，进程重启失效；SDK 形态的调用方（如农污内部会话）
 * 在会话初始化时调一次即可。多会话共享 JVM，故按 sessionId 隔离。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/skill-allowlist")
public class SessionSkillAllowlistController {

    private static final Logger log = LoggerFactory.getLogger(SessionSkillAllowlistController.class);

    @Autowired
    private SessionMapper sessionMapper;

    // @Lazy：SubagentTool 依赖大量 Tool bean，直接注入会在控制器初始化期拉起整条工具链。
    @Autowired(required = false)
    @Lazy
    private SubagentTool subagentTool;

    /**
     * 设置或清除会话技能白名单。
     *
     * <p>两种模式：
     * <ul>
     *   <li><b>手工名单</b>：{@code {"skills": ["a","b"]}}；{@code skills=null} 或缺省 = 清除；</li>
     *   <li><b>跟随人格</b>：{@code {"fromAgent": true}} —— 读该会话绑定的 {@code main_thread_agent}
     *       （或请求里显式指定的 {@code agentType}），把它 frontmatter {@code skills:} 点名的技能作为白名单。
     *       人格未点名技能 → 清除（不过滤），绝不映射成「全禁」。</li>
     * </ul>
     *
     * @param sessionId 会话 id
     * @param req       请求体
     */
    @PutMapping
    public SkillAllowlistDto put(@PathVariable String sessionId,
                                 @RequestBody(required = false) SkillAllowlistRequest req) {
        if (req != null && Boolean.TRUE.equals(req.fromAgent())) {
            String agentType = req.agentType();
            if (agentType == null || agentType.isBlank()) {
                SessionRecord row = sessionMapper != null ? sessionMapper.selectOneById(sessionId) : null;
                agentType = row != null ? row.getMainThreadAgent() : null;
            }
            if (agentType == null || agentType.isBlank()) {
                throw new ValidationException(
                    "会话未绑定主线程人格（main_thread_agent），且请求未指定 agentType");
            }
            if (subagentTool == null) {
                throw new ValidationException("SubagentTool 未接线，无法解析人格: " + agentType);
            }
            AgentDefinition def = subagentTool.agentRegistry(sessionId).findAgent(agentType.trim());
            if (def == null) {
                throw new ValidationException("人格不存在: " + agentType);
            }
            List<String> agentSkills = def.skills().orElse(List.of());
            SessionSkillAllowlistRegistry.putFromAgent(sessionId, agentSkills);
            log.info("[SessionSkillAllowlistController] PUT(session={}) fromAgent={} skills={}（空=不过滤）",
                sessionId, agentType, agentSkills);
        } else {
            List<String> skills = req != null ? req.skills() : null;
            SessionSkillAllowlistRegistry.put(sessionId, skills);
            log.info("[SessionSkillAllowlistController] PUT session={} skills={}（null=清除）", sessionId, skills);
        }
        return current(sessionId);
    }

    /** 读取当前白名单（{@code configured=false} = 未配置/不过滤）。 */
    @GetMapping
    public SkillAllowlistDto get(@PathVariable String sessionId) {
        return current(sessionId);
    }

    private static SkillAllowlistDto current(String sessionId) {
        List<String> skills = SessionSkillAllowlistRegistry.get(sessionId);
        return new SkillAllowlistDto(sessionId, skills != null, skills);
    }

    /**
     * 请求体。
     *
     * @param skills    手工白名单（null = 清除）
     * @param fromAgent true = 改用「会话绑定的主线程人格」的 skills
     * @param agentType fromAgent 模式下可显式指定人格（缺省取会话的 main_thread_agent）
     */
    public record SkillAllowlistRequest(List<String> skills, Boolean fromAgent, String agentType) {
    }

    /** 响应体：configured=false 时 skills 恒 null（未配置）；configured=true 时 skills 可为 []（全禁）。 */
    public record SkillAllowlistDto(String sessionId, boolean configured, List<String> skills) {
    }
}
