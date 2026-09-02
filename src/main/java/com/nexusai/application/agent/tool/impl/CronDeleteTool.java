package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * CronDelete 工具 · 对齐 CC {@code CronDeleteTool.ts} (P0.1 / CRON-A3).
 *
 * <p><b>L1 行为</b>: 通过 id 删除 schedule job. CC 的语义是
 * {@code removeCronTasks([id])} — Java 等价物是 {@code ScheduleService.delete(id)}
 * (DB + Quartz + sessionJobs 索引同步清理).
 *
 * <p><b>错误处理 (CRON-A3 · 对齐 CC {@code CronDeleteTool.ts:61-81} validateInput 预查)</b>:
 * <ul>
 *   <li>id 不存在 → {@link #validateInput} errorCode {@code "1"} +
 *       {@code No scheduled job with id '${id}'} (CC :64-69)，不再透传 NotFoundException 原文</li>
 *   <li>teammate 上下文存在且任务属另一 agent → errorCode {@code "2"} +
 *       {@code Cannot delete cron job '${id}': owned by another agent} (CC :71-79，
 *       "Teammates may only delete their own crons")</li>
 *   <li>execute() 兜底 catch → 通用错误消息（不泄露底层异常原文）</li>
 * </ul>
 *
 * <p><b>成功输出 (CRON-A3)</b>: tool_result 文本 {@code Cancelled job {id}.}（CC :90
 * {@code mapToolResultToToolResultBlockParam} content）。旧实现的取消状态 JSON 字段
 * 已删除（C18 删除，见 CRON-A3 deleteList）。
 *
 * <p><b>[CRON-A5] 门控</b>: 与 {@link CronCreateTool} 共用 {@code nexusai.feature.agent-trigger-cron}
 * 开关（matchIfMissing=true 默认开，对齐 CC 生产 AGENT_TRIGGERS 编译 true）+ 运行时 isEnabled()
 * 委托 {@link CronEnabledGates#isKairosCronEnabled()}（对齐 CC prompt.ts:36-45）。
 */
@Component
@ConditionalOnProperty(name = "nexusai.feature.agent-trigger-cron", havingValue = "true", matchIfMissing = true)
public class CronDeleteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronDeleteTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScheduleService scheduleService;

    /** 运行时门 · CC original: isKairosCronEnabled (prompt.ts:36-45). */
    private final CronEnabledGates cronGates;

    @Autowired
    public CronDeleteTool(ScheduleService scheduleService,
                          CronEnabledGates cronGates) {
        this.scheduleService = scheduleService;
        this.cronGates = cronGates;
    }

    @Override
    public String name() {
        return "CronDelete";
    }

    @Override
    public String description() {
        // [G23②] 对齐 CC prompt.ts:123 CRON_DELETE_DESCRIPTION 逐字（原 "(returned from CronCreate)"
        //   为 Java 自添，偏离 CC）
        return "Cancel a scheduled cron job by ID";
    }

    /**
     * [G23②] 工具提示词 · 对齐 CC CronDeleteTool.ts:44-45 prompt() = buildCronDeletePrompt
     * （prompt.ts:124-128）逐字移植（介质措辞中性化：.claude/scheduled_tasks.json → the scheduled task store）。
     */
    @Override
    public String prompt() {
        boolean durable = cronGates == null || cronGates.isDurableCronEnabled();
        return durable
            ? "Cancel a cron job previously scheduled with CronCreate. Removes it from the scheduled task "
                + "store (durable jobs) or the in-memory session store (session-only jobs)."
            : "Cancel a cron job previously scheduled with CronCreate. Removes it from the in-memory session store.";
    }

    /**
     * 搜索提示 · 对齐 CC CronDeleteTool.ts:37 searchHint = 'cancel a scheduled cron job'。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）。
     */
    @Override
    public String searchHint() {
        return "cancel a scheduled cron job";
    }

    /**
     * 结果落盘阈值 · 对齐 CC CronDeleteTool.ts:38 maxResultSizeChars = 100_000
     * （覆盖 Tool 接口默认 50000）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * 延迟执行 · 对齐 CC CronDeleteTool.ts:39 shouldDefer = true。
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 无条件加载 · 对齐 CC prompt.ts:65 alwaysLoad 豁免——cron 工具常驻初始 tools 数组
     * （模型直接可见，无需 ToolSearch 发现）。用户拍板 2026-08-29。
     */
    @Override
    public boolean alwaysLoad() {
        return true;
    }

    /**
     * 运行时启用 · 对齐 CC CronDeleteTool.ts:46-48 isEnabled() = isKairosCronEnabled()。
     * 委托 {@link CronEnabledGates}（agent-trigger-cron && !CLAUDE_CODE_DISABLE_CRON truthy）。
     *
     * <p><b>决策#11（CRON-F2 null 语义）</b>: 门控 null 统一 <b>fail-open</b>（null→开/放行）。
     * CC 布尔链无 null 态（isKairosCronEnabled 恒返回 boolean，GB 默认 true）；Java 侧
     * {@code cronGates == null} 视为门开——与 CronCreateTool.java:161 / CronListTool.java
     * 三层统一（参照锚点 CronCreateTool，B4 已改）。生产 @ConfigurationProperties +
     * @DefaultValue 恒注入非 null，此分支仅防护直接 new 构造/测试路径。
     */
    @Override
    public boolean isEnabled() {
        return cronGates == null || cronGates.isKairosCronEnabled();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        // CC CronDeleteTool.ts:20-24 z.strictObject({id}) → additionalProperties:false（拒绝未知键）。
        // IMP-F2（组 5-3 / △-13 修正）：旧实现无该声明，模型传入多余字段运行时放行，偏离 CC 前置拦截。
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.set("id", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "Job ID returned by CronCreate."));
        schema.putArray("required").add("id");
        return schema;
    }

    /**
     * 输出 schema · 对齐 CC {@code CronDeleteTool.ts:27-31}
     * {@code outputSchema = z.object({ id: z.string() })}。
     *
     * <p>成功路径 CC call 返回 {@code {data: {id}}}（CronDeleteTool.ts:84），本 schema
     * 描述该输出结构供输出验证/文档生成使用；tool_result 人类文本
     * {@code Cancelled job {id}.} 由 {@code mapToolResultToToolResultBlockParam}
     * （CronDeleteTool.ts:86-92）渲染，与 schema 并存不冲突。
     *
     * @return JSON Schema {@code {type: object, properties: {id: {type: string}}, required: [id]}}
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        schema.putArray("required").add("id");
        return schema;
    }

    /**
     * validateInput 预查 · 对齐 CC {@code CronDeleteTool.ts:61-81}.
     *
     * <p>两阶段预查（CC :62-79）:
     * <ol>
     *   <li><b>存在性</b>: {@code listAllCronTasks().find(t.id === input.id)} 找不到 →
     *       errorCode {@code "1"} + {@code No scheduled job with id '${id}'}（CC :64-69）</li>
     *   <li><b>所有权</b>: {@code getTeammateContext()} 存在且 {@code task.agentId !== ctx.agentId}
     *       → errorCode {@code "2"} + {@code Cannot delete cron job '${id}': owned by another agent}
     *       （CC :71-79 "Teammates may only delete their own crons."）</li>
     * </ol>
     *
     * <p><b>风险登记 (CRON-A3 / IMP-F2 修正)</b>: {@code ScheduleDto.agentId} 由 WF-B 填充
     * （当前生产恒 null）、TeammateContext 生产端 0 设定（@deprecated）→ errorCode 2 分支生产
     * 不可达；本实现按 CC {@code task.agentId !== ctx.agentId} 严格不等式结构对齐
     * （IMP-F2 / △-14：{@link Objects#equals} 语义，任一为 null 另一端非 null → 拒绝）。
     *
     * @param input 工具输入 {@code {id: string}}
     * @param ctx   工具调用上下文（本实现不消费；所有权判断用全局 {@link TeammateContext}，对齐 CC :72）
     * @return {@link ValidationResult#pass()} 或 fail("1"/"2", CC 精确消息)
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String id = input == null ? null : input.path("id").asText(null);
        if (id == null || id.isBlank()) {
            // CC :64-69 语义：空/缺失 id 等同不存在（schema strictObject 前置拦截兜底）
            return ValidationResult.fail("1", "No scheduled job with id ''");
        }
        // CC :62-69 预查存在性
        ScheduleDto task = scheduleService.listAll().stream()
            .filter(t -> id.equals(t.id()))
            .findFirst()
            .orElse(null);
        if (task == null) {
            return ValidationResult.fail("1", "No scheduled job with id '" + id + "'");
        }
        // CC :71-79 "Teammates may only delete their own crons." — 所有权预查
        TeammateContext teammate = TeammateContext.getTeammateContext();
        if (teammate != null) {
            // CC original: task.agentId !== ctx.agentId (CronDeleteTool.ts:73) — teammate 只能删自己的任务。
            // IMP-F2（组 5-3 / △-14 修正）：严格不等式语义——两端均 null 视为相等（CC undefined !== undefined → pass）；
            // 任一为 null 另一端非 null → 拒绝（CC undefined !== 'A' / 'A' !== undefined → reject）。
            // 旧实现 ownerAgentId != null 守卫在任务 agentId=null 时放行，偏离 CC（agentId null 边界）。
            String ownerAgentId = task.agentId();
            String ctxAgentId = teammate.getData().agentId();
            if (!Objects.equals(ownerAgentId, ctxAgentId)) {
                return ValidationResult.fail("2",
                    "Cannot delete cron job '" + id + "': owned by another agent");
            }
        }
        return ValidationResult.pass();
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        String id = call.input().path("id").asText(null);
        try {
            scheduleService.delete(id);
            if (log.isDebugEnabled()) {
                log.debug("CronDeleteTool: 已删除定时任务 id={}（对齐 CC CronDeleteTool.ts:83 removeCronTasks）", id);
            }
            log.info("CronDeleteTool: 删除定时任务成功 id={}", id);
            // CC :86-92 mapToolResultToToolResultBlockParam → tool_result 内容 =
            // "Cancelled job ${id}."（人类文本，非 JSON）—— 输出仅 {id} 语义
            return ToolResult.success(call.id(), "Cancelled job " + id + ".");
        } catch (Exception e) {
            log.warn("CronDeleteTool: 删除定时任务失败 id={}: {}", id, e.getMessage());
            // 兜底通用消息：validateInput 已挡不存在的 id；不再透传 NotFoundException 原文
            return ToolResult.error(call.id(), "CronDeleteTool: delete failed for id " + id);
        }
    }

    /**
     * 自动分类器输入 · 对齐 CC {@code CronDeleteTool.ts:49-51}
     * {@code toAutoClassifierInput(input) { return input.id }}。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("id").asText("");
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("cron_delete_allow"),
            null, false, null, List.of());
    }
}
