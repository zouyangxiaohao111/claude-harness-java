package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolErrorFormatter;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 1c 规则检查：调 {@code tool.checkPermissions(input, ctx)} 拿工具自己的决策并共享
 *
 * <h2>对齐 CC</h2>
 * <p>{@code utils/permissions/permissions.ts:1208-1223} - 1c 层
 * <p>对应伪代码（CC 真源行为）：<pre>
 *   let toolPermissionResult = { behavior: 'passthrough', message: createPermissionRequestMessage(tool.name) }
 *   try {
 *     const parsedInput = tool.inputSchema.parse(input)
 *     toolPermissionResult = await tool.checkPermissions(parsedInput, context)
 *   } catch (e) {
 *     if (e instanceof AbortError || e instanceof APIUserAbortError) { throw e }
 *     logError(e)
 *   }
 * </pre>
 *
 * <h2>检查逻辑</h2>
 * <p>调 {@link Tool#checkPermissions(JsonNode, ToolUseContext)} 让工具基于内容表态，
 * 结果存入 {@link ToolCheckCache}（ThreadLocal per-call），供 1d/1e/1f/1g/2a/2b/3 复用
 * （对齐 CC in-scope 共享 {@code toolPermissionResult}）：
 * <ul>
 *   <li><b>safeParseSchema 门</b>（OPD-WF3-01-08）：1c 前用 {@link ToolInputValidator#safeParseSchema}
 *       （= zod {@code tool.inputSchema.parse}，CC permissions.ts:1215）解析 input；
 *       parse 失败 → logError 等价 → 保持默认 passthrough 继续 2a/3 ask（CC catch →
 *       passthrough 语义）。</li>
 *   <li><b>catch 降级</b>（OPD-WF3-01-08）：{@code tool.checkPermissions} 抛异常时
 *       AbortError 等价（{@link AbortException}）重抛，其余 logError → 默认 passthrough
 *       （CC permissions.ts:1217-1223）。</li>
 *   <li><b>无 Allow 早返</b>（WF3-02 DC-04 + OPD-WF3-02-3）：CC 1c 无早返，工具 Allow
 *       仍流经 2a/2b/3（decisionReason 可被 Mode/Rule 覆盖，permissions.ts:1210-1318）。
 *       故本层<b>恒返回 null</b>，由后续层按归因消费。</li>
 * </ul>
 *
 * <p>本层是 10 层中唯一工具主动参与决策的地方。其他层都是基于规则的
 * （user/project/local settings 配置），1c 让工具基于内容表态。
 */
public class CheckLayer1c_ToolCheck implements CheckLayer {

    private static final Logger log = LoggerFactory.getLogger(CheckLayer1c_ToolCheck.class);

    /**
     * 工具输入验证器 · {@code null} = 未接线（测试/手动构造）。
     *
     * <p>WHY: 1c 需补 CC {@code inputSchema.parse} 门禁（permissions.ts:1215
     * {@code tool.inputSchema.parse(input)}）；Java 端等价 =
     * {@link ToolInputValidator#safeParseSchema}（zod safeParse, toolOrchestration.ts:97-107）。
     * 与 {@code HookPermissionResolver} 同模式：默认自建实例（无状态纯函数），可由
     * {@link #setInputValidator} 注入 Spring 单例。
     */
    private ToolInputValidator inputValidator = new ToolInputValidator();

    /**
     * 权限弹窗消息生成器 · 对齐 CC {@code createPermissionRequestMessage}
     * (permissions.ts:137-211)。默认实例（无 Spring 时亦可用，纯无状态类）；
     * {@link PermissionPipeline} 通过 {@link #setMessageGenerator} 注入 Spring 单例。
     */
    private PermissionMessageGenerator messageGenerator = new PermissionMessageGenerator();

    /**
     * 注入工具输入验证器 · 由 {@code PermissionPipeline} 接线（@Autowired(required=false)）。
     *
     * @param inputValidator 工具输入验证器；null 时忽略，保留默认实例
     */
    public void setInputValidator(ToolInputValidator inputValidator) {
        if (inputValidator != null) {
            this.inputValidator = inputValidator;
        }
    }

    /**
     * 注入消息生成器（PermissionPipeline 构造/后置接线）。
     *
     * @param messageGenerator 弹窗消息生成器（null 时忽略，保留默认实例）
     */
    public void setMessageGenerator(PermissionMessageGenerator messageGenerator) {
        if (messageGenerator != null) {
            this.messageGenerator = messageGenerator;
        }
    }

    /**
     * 执行 1c 层检查：调工具的 {@code checkPermissions}（前置 safeParseSchema 门 + catch 降级）。
     *
     * <p><b>恒返回 null</b>（对齐 CC 1c 无早返）：工具 Allow/Deny/Ask/Passthrough 全部
     * 交由后续层按归因消费（1d deny、1e requiresUserInteraction、1f rule 归因 ask、
     * 1g safetyCheck、2a bypass、2b allow、3 passthrough→ask/原样透传）。
     *
     * @param tool     工具实例（被调用方）
     * @param call     LLM 的工具调用请求
     * @param input    已解析的 JSON 输入（传给 tool）
     * @param ctx      工具调用上下文（传给 tool）
     * @param permCtx  权限上下文（1c 不需要）
     * @return         恒 null（继续管线，无 Allow 早返）
     */
    @Override
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        // 1. safeParseSchema 门 + tool.checkPermissions + catch 降级（OPD-WF3-01-08）
        //    CC permissions.ts:1214-1223：toolPermissionResult 默认 passthrough，
        //    try { parsedInput = tool.inputSchema.parse(input); toolPermissionResult =
        //    await tool.checkPermissions(parsedInput, context) } catch { Abort 重抛 /
        //    logError }。Java 等价：safeParseSchema 返回 fail（zod 等价）→ logError →
        //    默认 passthrough；AbortException 重抛（CC AbortError 等价）。
        PermissionResult toolDecision = null;
        ToolErrorFormatter.SafeParseResult parsed = inputValidator.safeParseSchema(tool, input);
        if (parsed.ok()) {
            try {
                toolDecision = tool.checkPermissions(parsed.value(), ctx);
            } catch (AbortException e) {
                // CC permissions.ts:1219-1221 —— AbortError / APIUserAbortError 重抛透传
                throw e;
            } catch (Exception e) {
                // CC permissions.ts:1222 logError(e) —— 工具 checkPermissions 异常降级为
                //   默认 passthrough，不阻断管线（继续 2a/3 ask）
                log.error("CheckLayer1c: tool={} checkPermissions 异常（对齐 CC permissions.ts:1222 logError, 降级 passthrough）: {}",
                    tool.name(), e.toString());
                toolDecision = null;
            }
        } else {
            // CC zod parse 失败抛错 → catch → logError → 默认 passthrough
            //   （permissions.ts:1218-1223）；Java safeParseSchema 返回 fail（不抛），显式降级
            log.error("CheckLayer1c: tool={} inputSchema.parse 失败（对齐 CC permissions.ts:1218-1223 logError, 降级 passthrough）issues={}",
                tool.name(), parsed.issues());
            toolDecision = null;
        }
        if (toolDecision == null) {
            // CC permissions.ts:1210-1213 默认值：{behavior:'passthrough', message:
            //   createPermissionRequestMessage(tool.name)}
            toolDecision = new PermissionResult.Passthrough(
                messageGenerator.createPermissionRequestMessage(tool.name(), null),
                null, List.of(), null, null);
        }
        // 2. [s03 P3 #12 修补] 把 tool.checkPermissions 结果存入 ThreadLocal cache，
        //    1d/1e/1f/1g/2a/2b/3 复用避免重调。5 工具当前纯函数,cache 安全。
        ToolCheckCache.put(tool.name(), toolDecision);
        // 3. [WF3-02 DC-04 + OPD-WF3-02-3] 去除 Allow 短路早返——CC 1c 无早返，工具 Allow
        //    仍流经 2a/2b/3（decisionReason 可被 Mode/Rule 覆盖，permissions.ts:1210-1318）。
        //    Deny / Ask / Passthrough 同样交给 bypass-immune 层 + 2a/2b/3 归因消费。
        return null;
    }
}
