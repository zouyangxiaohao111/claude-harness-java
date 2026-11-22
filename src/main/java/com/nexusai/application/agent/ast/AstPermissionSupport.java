package com.nexusai.application.agent.ast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.List;

/**
 * 多路径工具的权限合并 · 「逐路径各查一次，取最严」。
 *
 * <h2>为什么要逐路径查</h2>
 * AstGrep/AstEdit 接受多个目标（{@code ;} 分隔 / 数组）。权限管线只按 {@code tool.getPath(input)}
 * 取<b>一个</b>路径，直接放行会让「path 里混一个未授权目录」绕过检查。因此本类对每个路径构造
 * 合成输入 {@code {"path": p}} 各调一次 checker（checker 内部读 {@code tool.getPath}），再按
 * Deny(3) &gt; Ask(2) &gt; Passthrough(1) &gt; Allow(0) 取最严；同档保留第一个（消息更贴近首个路径）。
 *
 * <p>AstEdit 的 {@code apply=true} 视为写：最终若仍是 Allow，则升级为 {@link PermissionResult.Ask}
 * （写盘必须让用户看到改动清单）；Deny 直接透传（不弹窗）。
 */
public final class AstPermissionSupport {

    private AstPermissionSupport() {}

    /**
     * 逐路径检查并合并。
     *
     * @param tool        工具实例（用于 checker 的 {@code getPath(input)} 与规则匹配）
     * @param ctx         调用上下文
     * @param rawPaths    原始路径串（已在工具层 trim 过；可为空 → 直接 Ask）
     * @param readChecker 读权限 checker（{@code write=false} 时必填）
     * @param writeChecker 写权限 checker（{@code write=true} 时必填）
     * @param write       本工具是否写盘
     * @param askMessage  写盘升级 Ask 时的消息（含文件数/清单）
     * @return 最严决策
     */
    public static PermissionResult strictest(Tool tool, ToolUseContext ctx, List<String> rawPaths,
                                             ReadPermissionChecker readChecker,
                                             WritePermissionChecker writeChecker,
                                             boolean write, String askMessage,
                                             JsonNode originalInput) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return ask("Ast permission check needs at least one explicit path", originalInput);
        }
        PermissionResult strictest = null;
        int strictestRank = -1;
        for (String raw : rawPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ObjectNode one = JsonNodeFactory.instance.objectNode();
            one.put("path", raw);
            PermissionResult result = write
                ? requireWrite(writeChecker).check(tool, one, ctx)
                : requireRead(readChecker).check(tool, one, ctx);
            int rank = rank(result);
            if (rank > strictestRank) {
                strictest = result;
                strictestRank = rank;
            }
        }
        if (strictest == null) {
            // 全部为空串（上游已过滤，这里兜底）：不放行
            return ask("Ast permission check needs at least one explicit path", originalInput);
        }
        if (write && strictest instanceof PermissionResult.Allow) {
            return ask(askMessage == null || askMessage.isBlank()
                ? "AstEdit will rewrite files. Apply?" : askMessage, originalInput);
        }
        return withOriginalInput(strictest, originalInput);
    }

    /**
     * 把 checker 决策里的 {@code updatedInput} 换回<b>原始工具输入</b>。
     *
     * <p>⛔ 不能把合成节点 {@code {"path": p}} 原样返回：执行器看到
     * {@code Allow.updatedInput != permissionInput} 会把整个工具入参替换成合成节点
     * （{@code StreamingToolExecutor} 的 ask-answers 应用分支），随后 {@code execute} 只能看到
     * {@code path} 一个键 —— 真实调用会直接报「缺少 pat/ops」。决策类型/消息/建议原样保留。
     */
    private static PermissionResult withOriginalInput(PermissionResult result, JsonNode originalInput) {
        if (originalInput == null) {
            return result;
        }
        if (result instanceof PermissionResult.Allow a) {
            return new PermissionResult.Allow(originalInput, a.reason(), a.toolUseID(),
                a.userModified(), a.acceptFeedback(), a.contentBlocks());
        }
        if (result instanceof PermissionResult.Ask a) {
            return new PermissionResult.Ask(a.message(), a.reason(), a.suggestions(), a.blockedPath(),
                originalInput, a.metadata(), a.isBashSecurityCheckForMisparsing(),
                a.pendingClassifierCheck(), a.contentBlocks());
        }
        return result;
    }

    private static ReadPermissionChecker requireRead(ReadPermissionChecker checker) {
        if (checker == null) {
            throw new IllegalStateException("ReadPermissionChecker 未注入, 无法执行 AST 工具读权限检查");
        }
        return checker;
    }

    private static WritePermissionChecker requireWrite(WritePermissionChecker checker) {
        if (checker == null) {
            throw new IllegalStateException("WritePermissionChecker 未注入, 无法执行 AST 工具写权限检查");
        }
        return checker;
    }

    /** Deny(3) &gt; Ask(2) &gt; Passthrough(1) &gt; Allow(0)。 */
    private static int rank(PermissionResult result) {
        if (result instanceof PermissionResult.Deny) {
            return 3;
        }
        if (result instanceof PermissionResult.Ask) {
            return 2;
        }
        if (result instanceof PermissionResult.Passthrough) {
            return 1;
        }
        return 0;
    }

    /** 无路径 / 空路径兜底 Ask（fail-closed，静默放行会绕过权限层）。 */
    private static PermissionResult ask(String message, JsonNode originalInput) {
        return new PermissionResult.Ask(message, null, List.of(), null, originalInput, null, false, null,
            List.of());
    }

    /** 合成输入的 JSON 形状常量（测试与调用方共用，避免两处各写一份）。 */
    public static JsonNode syntheticInput(String path) {
        ObjectNode one = JsonNodeFactory.instance.objectNode();
        one.put("path", path);
        return one;
    }

    /** 供调用方构造 Ask（写盘升级 / 无路径兜底）· 与 {@link PermissionResult.Ask} 同形。 */
    public static PermissionResult askResult(String message, JsonNode originalInput) {
        return ask(message, originalInput);
    }
}
