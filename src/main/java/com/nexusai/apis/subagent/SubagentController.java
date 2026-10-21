package com.nexusai.apis.subagent;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.infra.exception.NotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 子代理详情端点 · 前端点击子代理卡片查看完整执行记录（transcript）。
 *
 * <p>数据源 = {@link AgentTranscript} 磁盘持久化（{@code {sessionDir}/subagents/agent-{agentId}.jsonl}，
 * 对齐 CC getAgentTranscript，sessionStorage.ts:4190-4236）。sessionDir 解析复用
 * {@link ResumeService#resolveSessionDir}（config-home 项目 slug 目录，与 SubagentExecutor 写入同源）。
 *
 * <p>agentId 三键查找（Bug B 修复）：transcript 文件名键为 a+16hex（SubagentExecutor 写入用
 * agentIdHex）；前端 SubagentIdentity.taskId 为后台任务 id → 先试原样（a+16hex / 旧 UUID 键），
 * UUID 形再经 {@link AgentContext#unpackAgentId} 转 a+16hex 兜底，最后按 taskId 查
 * {@link BackgroundTaskRunner#resolveOutputTask} 取 {@code task.agentId()}（子代理 UUID）还原
 * a+16hex 兜底（对齐 ResumeService.resumeAgentBackground 双键查找模式 + Bug B 补 taskId→agentId
 * 桥）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/subagents")
public class SubagentController {

    private static final Logger log = LoggerFactory.getLogger(SubagentController.class);

    /**
     * 后台任务运行器（可空 —— 无 Spring 容器直构/测试时 null，taskId 兜底分支跳过）。
     *
     * <p>供 getTranscript 的 taskId 兜底：前端 SubagentIdentity.taskId = BackgroundTask.id，
     * 经 {@link BackgroundTaskRunner#resolveOutputTask} 查 task → {@code task.agentId()}
     * （子代理 UUID）→ {@link AgentContext#unpackAgentId} 还原 a+16hex → 查 transcript。
     */
    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    /**
     * 会话的子代理摘要列表 · {@code GET /api/v1/sessions/{sessionId}/subagents?limit=50}
     *
     * <p>历史会话的 transcript 只活在磁盘 `subagents/agent-*.jsonl`（CC 同构），
     * 而 `/tasks` 只有进程内的后台任务（重启即丢）—— 这个端点补上「看得见历史子代理」。
     * 按文件 mtime 倒序，默认 50 条（实测单会话可达 5513 个，不能全量）。
     *
     * @param sessionId 会话 ID
     * @param limit     最多返回条数（默认 50，<=0 表示不限制）
     */
    @GetMapping
    public List<SubagentSummary> listSubagents(
            @PathVariable String sessionId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        Path sessionDir = ResumeService.resolveSessionDir(sessionId);
        List<String> ids = AgentTranscript.listAgentIds(sessionDir, sessionId, limit);
        List<SubagentSummary> out = new java.util.ArrayList<>(ids.size());
        for (String agentId : ids) {
            String agentType = null;
            String description = null;
            try {
                java.util.Optional<AgentTranscript.AgentMetadata> meta =
                    AgentTranscript.readMetadata(sessionDir, sessionId, agentId);
                if (meta.isPresent()) {
                    agentType = meta.get().agentType();
                    description = meta.get().description();
                }
            } catch (Exception e) {
                log.debug("读子代理 meta 失败 agentId={}: {}", agentId, e.getMessage());
            }
            String status = AgentTranscript.inferStatus(sessionDir, sessionId, agentId);
            out.add(new SubagentSummary(agentId, agentType, description, status));
        }
        log.info("[SubagentController] 列出会话子代理 sessionId={} limit={} → {} 条", sessionId, limit, out.size());
        return out;
    }

    /**
     * 子代理摘要（右栏历史列表用）。
     *
     * @param agentId     transcript 文件名里的 id（a+16hex）
     * @param agentType   agent 类型（meta.json，可能为空）
     * @param description 任务描述（meta.json，可能为空）
     * @param status      结局推断（completed/failed/unknown · 读 transcript 末行，meta 里没有此字段）
     */
    public record SubagentSummary(String agentId, String agentType, String description, String status) {}

    /** GET /api/v1/sessions/{sessionId}/subagents/{agentId}/transcript · 子代理完整执行记录（消息链）。 */
    @GetMapping("/{agentId}/transcript")
    public List<AgentMessage> getTranscript(
            @PathVariable String sessionId, @PathVariable String agentId) {
        Path sessionDir = ResumeService.resolveSessionDir(sessionId);
        Optional<AgentTranscript.AgentTranscriptResult> opt =
            AgentTranscript.getAgentTranscript(sessionDir, sessionId, agentId);
        if (opt.isEmpty()) {
            try {
                // UUID 形（前端 taskId）→ a+16hex 键兜底
                UUID uuid = UUID.fromString(agentId);
                String agentIdHex = AgentContext.unpackAgentId(uuid);
                opt = AgentTranscript.getAgentTranscript(sessionDir, sessionId, agentIdHex);
            } catch (Exception ignored) {
                // 非 UUID 形 → 原样键已试
            }
        }
        if (opt.isEmpty() && backgroundTaskRunner != null) {
            // Bug B 兜底: agentId 作为后台任务 taskId（BackgroundTask.id）→ task.agentId（子代理 UUID）
            // → unpackAgentId 还原 a+16hex → 查 transcript。注册链路 registerAsyncAgent/
            // registerAgentForeground 以 agentId 构建 taskId===agentId（BackgroundTaskRunner.java:618/683），
            // transcript 文件名经 unpackAgentId 得 a+16hex（SubagentExecutor.java:1436）；前端传的
            // taskId 非 packAgentId(agentIdHex) 结果时前两键 miss，此处经 task.agentId 桥接还原。
            BackgroundTask task = backgroundTaskRunner.resolveOutputTask(agentId);
            if (task != null && task.agentId() != null) {
                String agentIdHex = AgentContext.unpackAgentId(task.agentId());
                opt = AgentTranscript.getAgentTranscript(sessionDir, sessionId, agentIdHex);
                if (log.isDebugEnabled()) {
                    log.debug("子代理 transcript taskId 兜底: taskId={} 子代理UUID={} agentIdHex={} 命中={}",
                        agentId, task.agentId(), agentIdHex, opt.isPresent());
                }
            }
        }
        if (opt.isEmpty()) {
            log.warn("子代理 transcript 未找到: session={} agentId={}", sessionId, agentId);
            throw new NotFoundException("No transcript found for subagent: " + agentId);
        }
        List<AgentMessage> messages = opt.get().messages();
        if (log.isInfoEnabled()) {
            log.info("子代理 transcript 返回: session={} agentId={} messages={}", sessionId, agentId, messages.size());
        }
        return messages;
    }
}
