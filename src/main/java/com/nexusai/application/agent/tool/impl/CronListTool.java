package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.agent.tool.cron.CronToHuman;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.infra.util.StringWidth;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CronList 工具 · 对齐 CC {@code CronListTool.ts} (P0.1 / CRON-A4).
 *
 * <p><b>L1 行为 (CRON-A4 对齐 CC 真源)</b>:
 * <ul>
 *   <li>{@code call()} 取 {@code listAllCronTasks()} → {@code getTeammateContext()} 存在时按
 *       {@code agentId === ctx.agentId} 过滤（CronListTool.ts:64-69）；无 ctx → 全部
 *       （team lead 视角）</li>
 *   <li>jobs 映射 6 字段 {@code {id,cron,humanSchedule,prompt,recurring?,durable?}}（:70-77），
 *       条件键：{@code recurring===true} 才含 {@code recurring:true}，{@code durable===false}
 *       才含 {@code durable:false}（:75-76）</li>
 *   <li>{@code mapToolResultToToolResultBlockParam}（:80-93）tool_result 为人类列表文本
 *       {@code ${id} — ${humanSchedule}${(recurring)|(one-shot)}${[session-only]}: ${truncate(prompt,80,true)}}，
 *       空列表 → {@code No scheduled jobs.}</li>
 * </ul>
 *
 * <p><b>Java 映射偏差登记</b>:
 * <ul>
 *   <li>CC {@code t.prompt} → Java {@code ScheduleDto.command}（CronCreateTool 写入 prompt →
 *       command，CronCreateTool.java:41/250）</li>
 *   <li>{@code recurring} 由 {@code kind != ONCE} 派生（ScheduleDto 无真实布尔，risk R3）；</li>
 *   <li>{@code durable===false} 由 {@code scope == SESSION} 派生（SESSION=session-only 非持久，
 *       risk R3）；</li>
 *   <li>{@code getTeammateContext()} → {@link TeammateContext#getTeammateContext()}（与
 *       CronDeleteTool 同源），agentId 由 WF-B 填充（当前恒 null，risk R2，结构对齐）</li>
 *   <li>{@code truncate(prompt,80,true)} 复刻 CC truncate.ts:134-158（单行 + 宽度感知 '…'），
 *       宽度计算用 {@link StringWidth}（对齐 CC stringWidth）。</li>
 *   <li><b>CAND-3（r2 整合版 §六）</b>: 工具层 {@code catch(Exception)} + {@code e.getMessage()}
 *       泄漏已删除——异常抛框架层 StreamingToolExecutor 统一错误面（对齐 CC CronListTool.ts:63-79
 *       call() 无 catch；ToolErrorFormatter.formatError → {@code <ExceptionClass>: <message>}）。</li>
 * </ul>
 *
 * <p><b>[CRON-A5] 门控</b>: 与 {@link CronCreateTool} 共用 {@code nexusai.feature.agent-trigger-cron}
 * 开关（matchIfMissing=true 默认开）+ 运行时 isEnabled() 委托 {@link CronEnabledGates#isKairosCronEnabled()}。
 */
@Component
@ConditionalOnProperty(name = "nexusai.feature.agent-trigger-cron", havingValue = "true", matchIfMissing = true)
public class CronListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronListTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ScheduleService scheduleService;

    /** 运行时门 · CC original: isKairosCronEnabled (prompt.ts:36-45). */
    private final CronEnabledGates cronGates;

    @Autowired
    public CronListTool(ScheduleService scheduleService, CronEnabledGates cronGates) {
        this.scheduleService = scheduleService;
        this.cronGates = cronGates;
    }

    @Override
    public String name() {
        return "CronList";
    }

    @Override
    public String description() {
        // [G23②] 对齐 CC prompt.ts:130 CRON_LIST_DESCRIPTION 逐字（原 "(durable + session-only)"
        //   为 Java 自添，偏离 CC）
        return "List scheduled cron jobs";
    }

    /**
     * [G23②] 工具提示词 · 对齐 CC CronListTool.ts:46-47 prompt() = buildCronListPrompt
     * （prompt.ts:131-135）逐字移植（介质措辞中性化：.claude/scheduled_tasks.json → the scheduled task store）。
     */
    @Override
    public String prompt() {
        boolean durable = cronGates == null || cronGates.isDurableCronEnabled();
        return durable
            ? "List all cron jobs scheduled via CronCreate, both durable (the scheduled task store) and session-only."
            : "List all cron jobs scheduled via CronCreate in this session.";
    }

    /**
     * 搜索提示 · 对齐 CC CronListTool.ts:39 searchHint = 'list active cron jobs'。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）。
     */
    @Override
    public String searchHint() {
        return "list active cron jobs";
    }

    /**
     * 结果落盘阈值 · 对齐 CC CronListTool.ts:40 maxResultSizeChars = 100_000
     * （覆盖 Tool 接口默认 50000）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * 延迟执行 · 对齐 CC CronListTool.ts:41 shouldDefer = true。
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
     * 运行时启用 · 对齐 CC CronListTool.ts:48-50 isEnabled() = isKairosCronEnabled()。
     * 委托 {@link CronEnabledGates}（agent-trigger-cron && !CLAUDE_CODE_DISABLE_CRON truthy）。
     *
     * <p><b>决策#11（CRON-F2 null 语义）</b>: 门控 null 统一 <b>fail-open</b>（null→开/放行）。
     * CC 布尔链无 null 态（isKairosCronEnabled 恒返回 boolean，GB 默认 true）；Java 侧
     * {@code cronGates == null} 视为门开——与 CronCreateTool.java:161 / CronDeleteTool.java
     * 三层统一（参照锚点 CronCreateTool，B4 已改）。生产 @ConfigurationProperties +
     * @DefaultValue 恒注入非 null，此分支仅防护直接 new 构造/测试路径。
     */
    @Override
    public boolean isEnabled() {
        return cronGates == null || cronGates.isKairosCronEnabled();
    }

    /**
     * 并发安全 · 对齐 CC CronListTool.ts:51-53 isConcurrencySafe() = true。
     * 只读 list 无副作用，可并发执行。
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读 · 对齐 CC CronListTool.ts:54-56 isReadOnly() = true。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        // CC CronListTool.ts:17 z.strictObject({}) → additionalProperties:false（拒绝未知键）。
        // IMP-F2（组 5-3 / △-15 修正）：旧实现无该声明，模型传入多余字段运行时放行，偏离 CC 前置拦截。
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }

    /**
     * 输出 schema · 对齐 CC {@code CronListTool.ts:20-33}
     * {@code outputSchema = z.object({ jobs: z.array(z.object({ id, cron, humanSchedule,
     * prompt, recurring?, durable? })) })}。
     *
     * <p>成功路径 CC call 返回 {@code {data: {jobs}}}（CronListTool.ts:78）；tool_result 人类
     * 列表文本由 {@code mapToolResultToToolResultBlockParam}（:80-93）渲染，与 schema 并存
     * （同 CronDeleteTool.outputSchema 文档化约定）。
     *
     * @return JSON Schema {@code {type: object, properties: {jobs: {type: array,
     *         items: {type: object, properties: {id,cron,humanSchedule,prompt,recurring,durable},
     *         required: [id, cron, humanSchedule, prompt]}}}}}
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode jobs = props.putObject("jobs");
        jobs.put("type", "array");
        ObjectNode items = jobs.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("id").put("type", "string");
        itemProps.putObject("cron").put("type", "string");
        itemProps.putObject("humanSchedule").put("type", "string");
        itemProps.putObject("prompt").put("type", "string");
        itemProps.putObject("recurring").put("type", "boolean");
        itemProps.putObject("durable").put("type", "boolean");
        // CC :24-27 id/cron/humanSchedule/prompt 为 z.string()（必填）；:28-29 recurring/durable
        // 为 z.boolean().optional()（可选）→ required 仅含前 4 项，与 CronDeleteTool.java:134/155 同构。
        ArrayNode required = items.putArray("required");
        required.add("id");
        required.add("cron");
        required.add("humanSchedule");
        required.add("prompt");
        return schema;
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // CAND-3: 无 catch —— 异常直接抛框架层 StreamingToolExecutor 统一错误面
        // （对齐 CC CronListTool.ts:63-79 call() 无 try/catch；E1 原则 CronDeleteTool.java:251-252）。
        List<ScheduleDto> all = scheduleService.listAll();
        List<ScheduleDto> filtered = filterByContext(all);
        String text = renderListResultText(filtered);
        if (log.isDebugEnabled()) {
            log.debug("CronListTool: 已渲染列表文本，任务 {} 条 → 过滤后 {} 条，tool_result 文本长度 {}",
                all.size(), filtered.size(), text.length());
        }
        log.info("CronListTool: 返回 {} 个定时任务（对齐 CC CronListTool.ts:78 data:{jobs}）", filtered.size());
        return ToolResult.success(call.id(), text);
    }

    /**
     * 过滤逻辑 · 对齐 CC CronListTool.ts:64-69:
     * <pre>
     * const ctx = getTeammateContext()
     * const tasks = ctx ? allTasks.filter(t => t.agentId === ctx.agentId) : allTasks
     * </pre>
     * Java 映射: {@link TeammateContext#getTeammateContext()} 非 null → 只保留
     * {@code task.agentId} 等于 teammate agentId 的任务（同 CronDeleteTool:195-203 结构）。
     * {@code ScheduleDto.agentId} 由 WF-B 填充（当前生产恒 null）、TeammateContext 生产端
     * 0 设定 → 本分支生产不可达，仅结构对齐（risk R2，登记 WF-B/WF-D）。
     */
    private static List<ScheduleDto> filterByContext(List<ScheduleDto> all) {
        TeammateContext teammate = TeammateContext.getTeammateContext();
        if (teammate == null) {
            return all;   // team lead 视角: 看全部（CC :67）
        }
        // CC original: t.agentId === ctx.agentId (CronListTool.ts:68)
        String ctxAgentId = teammate.getData().agentId();
        return all.stream()
            .filter(d -> ctxAgentId != null && ctxAgentId.equals(d.agentId()))
            .toList();
    }

    /**
     * tool_result 人类列表文本 · 对齐 CC CronListTool.ts:80-93
     * {@code mapToolResultToToolResultBlockParam}:
     * <pre>
     * jobs.length > 0
     *   ? jobs.map(j => `${j.id} — ${j.humanSchedule}${j.recurring ? ' (recurring)' : ' (one-shot)'}
     *                     ${j.durable === false ? ' [session-only]' : ''}: ${truncate(j.prompt, 80, true)}`)
     *        .join('\n')
     *   : 'No scheduled jobs.'
     * </pre>
     *
     * @param jobs 过滤后的任务列表
     * @return 逐行列表文本；空列表 → {@code No scheduled jobs.}
     */
    private static String renderListResultText(List<ScheduleDto> jobs) {
        if (jobs.isEmpty()) {
            return "No scheduled jobs.";
        }
        StringBuilder sb = new StringBuilder();
        for (ScheduleDto d : jobs) {
            // CC :73 humanSchedule=cronToHuman(t.cron)（CronToHuman 对齐 cron.ts:218-308）。
            // IMP-F2（组 5-3 / DEL-F2-02）：once 任务存储模型改存 cron（CronCreateTool once 分支
            // cronForSchedule=cron），无 runAt null 态 → humanSchedule 恒 cronToHuman(cron)（CC :73 逐字）。
            // 存量/非工具创建的 once 行 cron 仍可能为 null（REST 直建）→ fail-loud warn + "unknown"
            // （删除 formatRunAt，防 cronToHuman(null) NPE 的守卫保留但不再按 runAt 格式化）。
            String cron = d.cron();
            if (cron == null) {
                log.warn("CronListTool: 任务 cron 为 null（存量行/REST 直建 once），id={} runAt={}，"
                    + "humanSchedule 显示 unknown", d.id(), d.runAt());
            }
            String humanSchedule = (cron == null) ? "unknown" : CronToHuman.cronToHuman(cron);
            // CC :75-76 条件键 → 文本标记: recurring 派生 kind!=once, durable===false 派生 scope==SESSION
            String recurringMark = d.kind() != ScheduleKind.once ? " (recurring)" : " (one-shot)";
            String durableMark = d.scope() == ScheduleScope.SESSION ? " [session-only]" : "";
            // CC :89 truncate(j.prompt, 80, true) · Java prompt = ScheduleDto.command
            String prompt = truncate(d.command(), 80, true);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(d.id()).append(" — ").append(humanSchedule)
                .append(recurringMark).append(durableMark)
                .append(": ").append(prompt);
        }
        return sb.toString();
    }

    /**
     * 宽度感知截断 · 复刻 CC {@code truncate}（utils/truncate.ts:134-158，3 参 singleLine 语义）:
     * <pre>
     * if (singleLine) { 首 '\n' 截断; stringWidth+1 > maxWidth → truncateToWidth; 否则 result + '…' }
     * if (stringWidth(result) <= maxWidth) return result
     * return truncateToWidth(result, maxWidth)
     * </pre>
     * 宽度计算与截断委托 {@link StringWidth}（对齐 CC stringWidth + truncateToWidth）。
     *
     * @param str        待截断文本（CC original: str，truncate.ts:134）
     * @param maxWidth   最大终端列宽（CC original: maxWidth，truncate.ts:135）
     * @param singleLine 首换行截断（CC original: singleLine，truncate.ts:136，默认 false）
     * @return 截断后文本（'…' 尾缀）
     */
    private static String truncate(String str, int maxWidth, boolean singleLine) {
        String result = str;
        // CC truncate.ts:142-151 · singleLine 首 '\n' 截断
        if (singleLine) {
            int firstNewline = str.indexOf('\n');
            if (firstNewline != -1) {
                result = str.substring(0, firstNewline);
                if (StringWidth.stringWidth(result) + 1 > maxWidth) {
                    return StringWidth.truncateToWidth(result, maxWidth);
                }
                return result + "…";
            }
        }
        // CC truncate.ts:154-157
        if (StringWidth.stringWidth(result) <= maxWidth) {
            return result;
        }
        return StringWidth.truncateToWidth(result, maxWidth);
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("cron_list_allow"),
            null, false, null, List.of());
    }
}
