package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.team.SpawnInProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link TeammateRelauncher} 生产实现 · 委托 {@link SpawnInProcess#spawnInProcessTeammate}。
 *
 * <p><b>历史重放（B3）</b>：CC 的 {@code resumeInProcessTeammate} 把读到的 {@code resumeMessages}
 * 作为 resumed 的初始消息（claude.exe 2.1.328 偏移 356099332），并在成功文案里回报
 * {@code resumedMessageCount}（偏移 356176932）。本实现把调用方给的历史列表原样透传入
 * {@link SpawnInProcess.InProcessSpawnConfig#resumeMessages()}，并把<b>同一份列表的长度</b>
 * 作为 {@code resumedMessageCount} 返回 —— 文案里的 N 与实际重放条数同源。
 *
 * <p><b>失败分档</b>：spawn 的并发门拒绝（{@code Concurrent subagent limit reached...}）映射为
 * {@code session_full}；其余为 {@code resume_failed}。⛔ 不把失败伪装成成功。
 */
@Component
public class SpawnInProcessTeammateRelauncher implements TeammateRelauncher {

    private static final Logger log = LoggerFactory.getLogger(SpawnInProcessTeammateRelauncher.class);

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private SpawnInProcess spawnInProcess;

    /** 测试/接线用 setter。 */
    public void setSpawnInProcess(SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
    }

    @Override
    public TeammateRelaunchResult relaunch(String agentName, String teamName, String parentSessionId,
                                           String agentType, String prompt,
                                           List<AgentMessage> resumeMessages) {
        if (spawnInProcess == null) {
            log.warn("[TeammateRelauncher] SpawnInProcess 未注入，无法重拉 teammate {}", agentName);
            return TeammateRelaunchResult.failed("resume_failed",
                "in-process teammate relaunch is not available in this build.");
        }
        if (agentName == null || agentName.isBlank() || teamName == null || teamName.isBlank()) {
            log.warn("[TeammateRelauncher] 缺少 name/teamName，无法重拉（name={} team={}）", agentName, teamName);
            return TeammateRelaunchResult.failed("resume_failed",
                "the teammate has no name/team saved, so it cannot be started again.");
        }
        int replayCount = resumeMessages == null ? 0 : resumeMessages.size();
        try {
            SpawnInProcess.InProcessSpawnConfig config = new SpawnInProcess.InProcessSpawnConfig(
                agentName, teamName, prompt, null, false, null, agentType, null, resumeMessages);
            SpawnInProcess.InProcessSpawnOutput out = spawnInProcess.spawnInProcessTeammate(
                config, new SpawnInProcess.SpawnContext(parentSessionId, null));
            if (out != null && out.success()) {
                log.info("[TeammateRelauncher] teammate 已重拉: {} task={} 重放历史={} 条 "
                    + "(CC resumeInProcessTeammate)", out.agentId(), out.taskId(), replayCount);
                return TeammateRelaunchResult.resumed(out.agentId(), replayCount);
            }
            String error = out != null ? out.error() : "null output";
            String errorClass = error != null && error.startsWith("Concurrent subagent limit reached")
                ? "session_full" : "resume_failed";
            log.warn("[TeammateRelauncher] 重拉失败 {} class={}: {}", agentName, errorClass, error);
            return TeammateRelaunchResult.failed(errorClass, error);
        } catch (Exception e) {
            log.warn("[TeammateRelauncher] 重拉异常 {}: {}", agentName, e.toString());
            return TeammateRelaunchResult.failed("resume_failed", e.toString());
        }
    }
}
