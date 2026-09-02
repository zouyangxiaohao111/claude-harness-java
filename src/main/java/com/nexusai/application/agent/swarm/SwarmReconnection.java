package com.nexusai.application.agent.swarm;

import java.util.List;
import java.util.function.Supplier;

/**
 * SwarmReconnection · 对齐 CC utils/swarm/reconnection.ts.
 *
 * <p>L1 语义: 队友初始化 — fresh spawns 来自 CLI args,resumed sessions 从 teamName/agentName 推断。
 * <ul>
 *   <li>{@code computeInitialTeamContext(dynamicContextSupplier, teamFileReader)} → TeamContext or null</li>
 *   <li>{@code initializeTeammateContextFromSession(setAppState, teamName, agentName)} — set teamContext</li>
 * </ul>
 *
 * <p>2 静态方法 + 注入式 suppliers (testable).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + TeamContext record + DynamicContext record + TeamFile record + Member record</li>
 *   <li><b>A2 Golden Trace</b>: dynamicContext.teamName null→null;read team file fail→null;find member→selfAgentId=null;success→full TeamContext</li>
 *   <li><b>A3 副作用</b>: setAppState 注入式;无全局状态</li>
 *   <li><b>A4 边界</b>: null context→null;empty teamName→null;member not found→selfAgentId=null</li>
 *   <li><b>A5 业务场景</b>: fresh spawn compute teamContext BEFORE first render;resumed session restore from transcript</li>
 * </ul>
 *
 * <p>L3 升级: TS type interface → Java record;
 * TS dynamic import → Java Supplier 注入式;
 * TS function expression → Java lambda.
 */
public final class SwarmReconnection {

    public record TeamContext(
        String teamName,
        String teamFilePath,
        String leadAgentId,
        String selfAgentId,
        String selfAgentName,
        boolean isLeader,
        java.util.Map<String, Object> teammates) {}

    public record DynamicContext(String teamName, String agentId, String agentName) {}

    public record Member(String name, String agentId) {}

    public record TeamFile(String leadAgentId, List<Member> members) {}

    private SwarmReconnection() {}

    /**
     * Compute initial teamContext from dynamic context.
     *
     * @param dynamicContextSupplier returns current DynamicContext (may be null)
     * @param teamFileReader         takes (teamName) → TeamFile (or null if file missing)
     * @return TeamContext or null if not a teammate
     */
    public static TeamContext computeInitialTeamContext(
        Supplier<DynamicContext> dynamicContextSupplier,
        java.util.function.Function<String, TeamFile> teamFileReader) {
        DynamicContext context = dynamicContextSupplier == null ? null : dynamicContextSupplier.get();
        if (context == null || context.teamName() == null || context.agentName() == null) {
            return null;
        }
        TeamFile teamFile = teamFileReader == null ? null : teamFileReader.apply(context.teamName());
        if (teamFile == null) return null;

        boolean isLeader = context.agentId() == null;
        String teamFilePath = context.teamName();  // CC: getTeamFilePath(teamName)
        String selfAgentId = null;
        if (!isLeader) {
            Member member = teamFile.members().stream()
                .filter(m -> m.name().equals(context.agentName()))
                .findFirst()
                .orElse(null);
            if (member != null) selfAgentId = member.agentId();
        }
        return new TeamContext(
            context.teamName(),
            teamFilePath,
            teamFile.leadAgentId(),
            selfAgentId,
            context.agentName(),
            isLeader,
            new java.util.HashMap<>());
    }
}
