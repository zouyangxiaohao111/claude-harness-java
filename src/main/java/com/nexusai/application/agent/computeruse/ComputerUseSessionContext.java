package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.mcp.McpCallContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * computer-use 的会话上下文 · 对齐 CCB {@code packages/@ant/computer-use-mcp/src/types.ts}
 * 的 {@code ComputerUseSessionContext}（Java 侧最小形态）。
 *
 * <p><b>CCB 原形态</b>：一组 getter + callback（{@code getAllowedApps} / {@code getGrantFlags} /
 * {@code getUserDeniedBundleIds} / {@code getLastScreenshotDims} / {@code onPermissionRequest} …），
 * 由 host（CLI）在建 server 时用闭包绑定（{@code mcpServer.ts:89-253} 的
 * {@code bindSessionContext}）。
 *
 * <p><b>本仓形态</b>：会话态<b>本来就分桶存在</b>
 * {@link ComputerUseGrantStore}（授权位 / 应用白名单）/ {@link ComputerUseGeometryStore}
 * （截图几何）/ {@link ComputerUseGroundingStore}（grounding 定位帧）里，
 * 且 35 个工具实现<b>自带</b>档位门控与授权判定（不依赖 host 回调）。
 * <br>⇒ Java 侧只需把「本次调用属于哪个会话 + 给工具的 {@link ToolUseContext}」带过来，
 * 会话态由工具实现自己按 {@code sessionId} 去 store 里取 ⇒ <b>业务语义零改动</b>。
 *
 * @param sessionId      会话 id（必填；工具实现据此分桶取授权位 / 几何快照）
 * @param toolUseContext 交给底层 {@link com.nexusai.application.agent.tool.Tool} 的上下文
 *                       （工具读 {@code sessionId} / {@code agentId} /
 *                       {@code effectiveModelName} / {@code effectiveProviderType}）
 */
public record ComputerUseSessionContext(String sessionId, ToolUseContext toolUseContext) {

    public ComputerUseSessionContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("ComputerUseSessionContext.sessionId is required");
        }
    }

    /**
     * 由 MCP 调用信封构造会话上下文 · MCP server 侧派发给底层工具前的唯一入口。
     *
     * <p><b>构造的 ToolUseContext 只带 CU 工具真正消费的字段</b>：
     * {@code sessionId}（授权位 / 几何分桶）/ {@code agentId}（主线程 vs 子代理）/
     * {@code effectiveModelName} + {@code effectiveProviderType}（视觉能力闸）。
     *
     * <p><b>effectiveCwd 传空路径占位</b>：CU 的 35 个工具<b>无一</b>读 {@code effectiveCwd}
     * ⇒ 传 {@code Path.of("")} 以避开 {@code CwdResolution.getCwd(sessionId)} 的会话解析
     * （该解析在会话缺失时<b>抛错</b>，与 CU 调用无关却是纯失败源）。
     *
     * @param callContext MCP 调用信封（sessionId 必填）
     * @return 会话上下文
     */
    public static ComputerUseSessionContext of(McpCallContext callContext) {
        if (callContext == null || callContext.sessionId() == null || callContext.sessionId().isBlank()) {
            throw new IllegalArgumentException("MCP 调用信封缺 sessionId");
        }
        UUID agentId = null;
        if (callContext.agentId() != null && !callContext.agentId().isBlank()) {
            try {
                agentId = UUID.fromString(callContext.agentId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("MCP 调用信封 agentId 非法: " + callContext.agentId(), e);
            }
        }
        ToolUseContext ctx = new ToolUseContext(
                agentId,
                callContext.sessionId(),
                PermissionMode.DEFAULT,
                Map.of(),
                List.of(),
                "",
                AbortController.NOOP,
                List.of(),
                null,
                PermissionMode.DEFAULT,
                Map.of(),
                false,
                "",
                Path.of(""),
                null)
                .withEffectiveModelName(callContext.effectiveModelName())
                .withEffectiveProviderType(callContext.effectiveProviderType());
        return new ComputerUseSessionContext(callContext.sessionId(), ctx);
    }
}

