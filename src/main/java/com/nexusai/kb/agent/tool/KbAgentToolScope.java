package com.nexusai.kb.agent.tool;

import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.agent.KbAgentService;
import com.nexusai.kb.domain.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库工具的作用域解析 · 批次 W10。
 *
 * <p><b>为什么单独一个类</b>：四个知识库工具（search / list / read / graph）
 * 都要「本会话允许查哪些库」这一个判断。放进每个工具会让四份实现慢慢分叉
 * （本仓已多次踩过「同一语义两处各写一遍」的坑）。
 *
 * <p><b>解析链</b>：
 * <ol>
 *   <li>会话绑定智能体（{@code sessions.main_thread_agent}）
 *       → {@code kb_agent.knowledge_bases}</li>
 *   <li>没绑 / 没配 / 配成 {@code all} → {@code null}（= 全部可检索库）</li>
 *   <li>配成 {@code none} → 空列表（明确不检索）</li>
 * </ol>
 *
 * <p><b>⚠️ 已删除的库会被过滤掉</b>：配置里留着旧 id 是常见状态（库删了配置没清），
 * 不过滤的话工具会去查一个不存在的库，返回空结果，看起来像「知识库里没有」。
 */
@Component
public class KbAgentToolScope {

    private static final Logger log = LoggerFactory.getLogger(KbAgentToolScope.class);

    @Autowired(required = false)
    private KbAgentService kbAgentService;

    @Autowired(required = false)
    private com.nexusai.repository.session.mapper.SessionMapper sessionMapper;

    @Autowired(required = false)
    private KnowledgeBaseService knowledgeBaseService;

    /** 文档服务 · 仅用于把「请求里 @ 的文档 id」解析成它所属的知识库 id。 */
    @Autowired(required = false)
    private com.nexusai.kb.domain.KnowledgeService knowledgeService;

    /**
     * 本会话允许访问的知识库。
     *
     * @param ctx 工具上下文（取 sessionId）
     * @return {@code null} = 不限制；空列表 = 明确不允许；非空 = 白名单
     */
    public List<String> allowedKnowledgeBaseIds(ToolUseContext ctx) {
        // ① 会话/智能体级白名单（原逻辑）
        List<String> agentScope = resolveAgentScope(ctx);
        // ② 请求级范围（用户在 KB 面板 @ 的库/文件）—— 本轮唯一权威
        List<String> requestScope = requestScopeKnowledgeBaseIds(ctx);
        if (requestScope == null) {
            return agentScope;              // 未指定 ⇒ 与原行为逐字节一致（主会话走这条）
        }
        if (agentScope == null) {
            return requestScope;            // 无 agent 约束 ⇒ 直接用请求范围
        }
        // ③ 交集：两者都能看到才允许检索（⛔ 任一为空都不放行，避免「收窄失效」）
        List<String> merged = new ArrayList<>();
        for (String id : requestScope) {
            if (agentScope.contains(id)) {
                merged.add(id);
            }
        }
        if (merged.size() != requestScope.size()) {
            log.warn("[kb_agent 工具范围] 请求范围与智能体白名单取交集后收窄 {} → {} 个库",
                requestScope.size(), merged.size());
        }
        return merged;
    }

    /**
     * 请求级检索范围 · 读 {@code ToolUseContext.requestExtras()["kbScope"]}。
     *
     * <p>为什么由本类解析而不是网关：文档 → 父知识库的推导要查库（{@code kb_knowledge}），
     * 网关只搬运 id；这里已经有 {@link KnowledgeBaseService}，再加一个文档服务即可。
     *
     * @return {@code null} = 本轮没有请求级范围（主会话 / 用户没选）⇒ 调用方按原逻辑处理
     */
    private List<String> requestScopeKnowledgeBaseIds(ToolUseContext ctx) {
        Map<?, ?> scope = scopeMap(ctx);
        if (scope == null) {
            return null;
        }
        // ⭐ 优先读网关算好的「本轮完整读范围」。它与 `<runtime_context>` 里列的是同一份 id，
        //   两处口径必须一致（否则模型看到一个库、工具却查另一个）。
        List<String> effective = stringList(scope.get("effectiveKbIds"));
        if (!effective.isEmpty()) {
            return effective;
        }
        // 兼容没有 effectiveKbIds 的旧请求 / 其他调用方：退回「@ 的库 + @ 文件所属库」。
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(explicitKbIds(scope));
        return out.isEmpty() ? java.util.List.of() : new ArrayList<>(out);
    }

    /**
     * 本轮<b>可写</b>的知识库 · 对齐源站 {@code writableWikiKBIDs}（{@code agent_service.go:1292}）。
     *
     * <p>⭐ 与 {@link #allowedKnowledgeBaseIds} 分开是<b>必须</b>的：不 @ 时读范围会变成
     * 「全部库」，如果写工具也读那份范围，就等于顺手给了全部库的写权限。
     *
     * <p>口径（源站 {@code wiki_fixer} = {@code kb_selection_mode: "selected"}）：
     * <ul>
     *   <li>{@code builtin-wiki-fixer} ⇒ 只允许用户本轮 @ 的库（含 @ 文件所属库）；没 @ ⇒ 空</li>
     *   <li>其他人格 ⇒ 本轮读范围（源站 all 模式下有写权限就能写）</li>
     * </ul>
     *
     * @return 空列表 = 本轮没有可写库（调用方必须拒绝写操作）
     */
    public List<String> writableKnowledgeBaseIds(ToolUseContext ctx) {
        Map<?, ?> scope = scopeMap(ctx);
        if (scope == null) {
            return List.of();
        }
        // ⭐ 网关已按人格算好写范围（`writableKbIds`）—— 这里只读，不重算，三处口径必须同一份。
        //   ⚠️ 字段存在但为空 = 明确不可写，⛔ 不能当「没配置」去回落。
        if (scope.get("writableKbIds") instanceof List<?> rawWritable) {
            return stringList(rawWritable);
        }
        // 兼容没有 writableKbIds 的旧请求 / 其他调用方
        List<String> explicit = explicitKbIds(scope);
        if (com.nexusai.kb.agent.KbBuiltInAgentDefs.WIKI_FIXER.equals(boundAgentId(ctx))) {
            return explicit;
        }
        List<String> effective = stringList(scope.get("effectiveKbIds"));
        return effective.isEmpty() ? explicit : effective;
    }

    /** {@code requestExtras["kbScope"]} 的 Map 形态；没有就返回 null（主会话 / 没选）。 */
    private static Map<?, ?> scopeMap(ToolUseContext ctx) {
        Map<String, Object> extras = ctx == null ? null : ctx.requestExtras();
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        Object raw = extras.get("kbScope");
        return raw instanceof Map<?, ?> scope ? scope : null;
    }

    /** 把 JSON 数组读成字符串列表（忽略非字符串/空白项）。 */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 用户<b>真正 @ 的</b>知识库（库 + @ 文件所属库）。
     *
     * <p>⛔ 不读 {@code effectiveKbIds}：那是「本轮可读的全部库」，不是用户的选择。
     */
    private List<String> explicitKbIds(Map<?, ?> scope) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (scope.get("knowledgeBaseIds") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String id && !id.isBlank()) {
                    out.add(id);
                }
            }
        }
        // @ 的是单个文件 ⇒ 解析它所属的知识库（本仓检索工具目前只支持库级过滤）
        if (scope.get("knowledgeIds") instanceof List<?> list && knowledgeService != null) {
            for (Object item : list) {
                if (!(item instanceof String kid) || kid.isBlank()) {
                    continue;
                }
                try {
                    var dto = knowledgeService.getById(kid);
                    if (dto != null && dto.knowledgeBaseId() != null && !dto.knowledgeBaseId().isBlank()) {
                        out.add(dto.knowledgeBaseId());
                    }
                } catch (RuntimeException e) {
                    // 单个文档查不到不该放宽范围：跳过即可（宁可少查，不可多查）
                    log.warn("[kb_agent 工具范围] 请求范围里的文档 {} 解析失败，已忽略: {}", kid, e.toString());
                }
            }
        }
        return out.isEmpty() ? List.of() : new ArrayList<>(out);
    }

    /** 会话绑定的 agent id（写权限口径要用它区分 wiki-fixer）· 读不到返回 null。 */
    private String boundAgentId(ToolUseContext ctx) {
        if (sessionMapper == null || ctx == null) {
            return null;
        }
        String sessionId = ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var row = sessionMapper.selectOneById(sessionId);
            String agent = row == null ? null : row.getMainThreadAgent();
            return agent == null || agent.isBlank() ? null : agent.trim();
        } catch (RuntimeException e) {
            log.warn("[kb_agent 工具范围] 读会话失败（写权限按非 wiki-fixer 处理）session={} 原因={}",
                sessionId, e.toString());
            return null;
        }
    }

    /**
     * 会话绑定智能体的知识库白名单（原 {@code allowedKnowledgeBaseIds} 的实现，逐字未改）。
     */
    private List<String> resolveAgentScope(ToolUseContext ctx) {
        if (kbAgentService == null || sessionMapper == null) {
            return null;
        }
        String sessionId = ctx == null ? null : ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            com.nexusai.repository.session.entity.SessionRecord row = sessionMapper.selectOneById(sessionId);
            if (row == null || row.getMainThreadAgent() == null || row.getMainThreadAgent().isBlank()) {
                return null;
            }
            String agentId = row.getMainThreadAgent().trim();
            List<String> scope = kbAgentService.resolveKnowledgeBaseIds(agentId);
            if (scope == null || scope.isEmpty()) {
                return scope;
            }
            Set<String> existing = new HashSet<>();
            try {
                knowledgeBaseService.list().forEach(kb -> existing.add(kb.id()));
            } catch (RuntimeException e) {
                // 列表查不出来 ⇒ 不擅自收窄（宁可多查，不可静默跳过）
                log.warn("[kb_agent 工具范围] 库列表查询失败，跳过白名单过滤: {}", e.toString());
                return scope;
            }
            List<String> filtered = new ArrayList<>();
            for (String id : scope) {
                if (existing.contains(id)) {
                    filtered.add(id);
                }
            }
            if (filtered.size() != scope.size()) {
                log.warn("[kb_agent 工具范围] 智能体 {} 的知识库白名单有 {} 个库已不存在，已忽略",
                    agentId, scope.size() - filtered.size());
            }
            return filtered;
        } catch (RuntimeException e) {
            // fail-open：读配置失败 ⇒ 不限制（宁可多查，不可静默不查）
            log.warn("[kb_agent 工具范围] 解析失败，按不限制处理 session={} 原因={}",
                sessionId, e.toString());
            return null;
        }
    }
}
