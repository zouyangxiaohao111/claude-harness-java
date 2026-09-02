package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sleep 工具 · 对齐 CC {@code SleepTool.ts}（{@code src/tools/SleepTool/SleepTool.ts}）。
 *
 * <p>[G31①] 按 CC 真源重写（2026-08-22）：输入 {@code duration_seconds}（秒、无界，无 Java 旧
 * 60s 上限）、输出 {@code {slept_seconds, interrupted}}（CC {@code SleepOutput} :23）、abort
 * 接线（CC :162-175 {@code context.abortController.signal.addEventListener('abort', onAbort)}）、
 * notify 等价（CC :121-128/:199-210 {@code notifyAutomationStateChanged}，Java 无 proactive
 * 自动化状态 → N/A 登记）、S6 500ms 队列唤醒保留（CC :9/:179-183 wakeCheck，master 已覆盖勿重复）。
 *
 * <p>L1 语义: Agent 主动暂停执行, 用于等待外部异步事件完成 (e.g. cron job、远程任务、build 结束).
 *
 * <p><b>[R32-b7a-1]</b> feature flag 守卫: 默认不注册到 ToolRegistry · 对齐 CC
 * {@code PROACTIVE/KAIROS} 守卫. 启用: {@code nexusai.feature.proactive=true}.
 */
@Component
@ConditionalOnProperty(name = "nexusai.feature.proactive", havingValue = "true", matchIfMissing = false)
public class SleepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SleepTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC SleepTool.ts:9 — SLEEP_WAKE_CHECK_INTERVAL_MS = 500（wakeCheck 队列轮询间隔） */
    private static final long WAKE_CHECK_INTERVAL_MS = 500L;

    /**
     * CC messageQueueManager.ts 进程级命令队列 — {@code hasQueuedWakeSignal()} 唤醒源
     * （SleepTool.ts:46-49 {@code queue.hasCommandsInQueue()}）。
     *
     * <p>{@code @Autowired(required=false)} + null 守卫：非 Spring 场景（{@code new SleepTool()}
     * 测试 / proactive flag 关闭）保持裸 sleep 原行为；生产由 Spring 注入
     * （{@code TaskConfiguration.notificationQueue()} @Bean）。
     */
    @Autowired(required = false)
    private NotificationQueue notificationQueue;

    /** 测试 / 非 Spring 场景注入队列（对齐 {@code AgentLoopContextFactory.setNotificationQueue} 约定）。 */
    public void setNotificationQueue(NotificationQueue notificationQueue) {
        this.notificationQueue = notificationQueue;
    }

    @Override
    public String name() {
        // CC 原名: SLEEP_TOOL_NAME (Open-ClaudeCode/src/tools/SleepTool/prompt.ts:3) = 'Sleep'
        // 旧实现返回小写 'sleep' 偏离 CC 大写；现引用 ToolNameConstants 常量保证单点权威。
        if (log.isDebugEnabled()) {
            log.debug("SleepTool.name(): 返回 CC 工具名 SLEEP_TOOL_NAME='Sleep'（对齐 Open-ClaudeCode/src/tools/SleepTool/prompt.ts:3）");
        }
        return ToolNameConstants.SLEEP_TOOL_NAME;
    }

    @Override
    public String description() {
        // CC 原名: DESCRIPTION (Open-ClaudeCode/src/tools/SleepTool/prompt.ts:5) = 'Wait for a specified duration'
        // 旧实现为自定义长文本，与 CC 不符；description 是 LLM 判断何时调用工具的关键文案，逐字对齐。
        if (log.isDebugEnabled()) {
            log.debug("SleepTool.description(): 返回 CC DESCRIPTION='Wait for a specified duration'（对齐 Open-ClaudeCode/src/tools/SleepTool/prompt.ts:5）");
        }
        return "Wait for a specified duration";
    }

    @Override
    public String prompt() {
        // CC 原名: SLEEP_TOOL_PROMPT (Open-ClaudeCode/src/tools/SleepTool/prompt.ts:7-17)
        // 模板字面量插值 TICK_TAG='tick' (Open-ClaudeCode/src/constants/xml.ts:25) 后:
        // '<${TICK_TAG}>' → '<tick>' 已内联。逐字保留 em-dash「—」、反引号 `Bash(sleep ...)`、
        // 段落空行，无尾随换行。ToolRegistry.toOpenAiToolsArray 以 prompt() 非 null 优先作为
        // LLM 可见工具描述 (ToolRegistry.java:422-424)。
        if (log.isDebugEnabled()) {
            log.debug("SleepTool.prompt(): 返回 SLEEP_TOOL_PROMPT 逐字文本（对齐 CC prompt.ts:7-17，TICK_TAG 已内联为 <tick>）");
        }
        return """
            Wait for a specified duration. The user can interrupt the sleep at any time.

            Use this when the user tells you to sleep or rest, when you have nothing to do, or when you're waiting for something.

            You may receive <tick> prompts — these are periodic check-ins. Look for useful work to do before sleeping.

            You can call this concurrently with other tools — it won't interfere with them.

            Prefer this over `Bash(sleep ...)` — it doesn't hold a shell process.

            Each wake-up costs an API call, but the prompt cache expires after 5 minutes of inactivity — balance accordingly.""";
    }

    @Override
    public String interruptBehavior() {
        // CC 语义: interruptBehavior 'cancel' (Open-ClaudeCode/src/utils/handlePromptSubmit.ts:320 注释级,
        // e.g. SleepTool) + StreamingToolExecutor.ts:221-237 仅 'cancel' 工具被新消息中断取消。
        // sleep 是长时等待，用户发新消息应立即取消而非 block 等待。CC SleepTool 主体源缺失，
        // 此为最强可得证据（注释级）；Java StreamingToolExecutor 已支持该语义 (interruptBehavior() 消费点)。
        if (log.isDebugEnabled()) {
            log.debug("SleepTool.interruptBehavior(): 返回 'cancel'（对齐 CC handlePromptSubmit.ts:320 注释级 + StreamingToolExecutor.ts:221-237）");
        }
        return "cancel";
    }

    /**
     * 并发安全 · 对齐 CC SleepTool.ts:72-74 {@code isConcurrencySafe() { return true }}——
     * sleep 只占用本线程等待（不持有 shell/文件/全局状态），可与其他并发安全工具并行执行。
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读 · 对齐 CC SleepTool.ts:75-77 {@code isReadOnly() { return true }}——sleep 不改任何
     * 状态（无副作用，只消耗时间），故只读并发执行无需写入权限检查。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        // [G31①] 对齐 CC SleepTool.ts:11-19 z.strictObject({duration_seconds: z.number()})——
        // 秒、无 minimum/maximum 上限（删除 Java 旧 60s cap）；strictObject → additionalProperties:false。
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        var props = schema.putObject("properties");
        props.putObject("duration_seconds").put("type", "number")
            .put("description", "How long to sleep in seconds. Can be interrupted by the user at any time.");
        schema.putArray("required").add("duration_seconds");
        return schema;
    }

    /**
     * [G31①] 主体 · 对齐 CC SleepTool.ts:105-211（duration_seconds + 输出 {slept_seconds, interrupted}）。
     *
     * <p>唤醒机制保留 S6 500ms 队列轮询（master 已覆盖勿重复）：每 500ms 轮询
     * {@link NotificationQueue#hasCommandsInQueue()}（CC :179-183 wakeCheck），队列出现新命令
     * （含 cron 入队）→ 提前打断 → {@code interrupted=true}；另接 abort（CC :162-175
     * {@code abortController.signal.addEventListener('abort', onAbort)}）——外部用户取消 → 提前
     * 打断。睡满 → {@code interrupted=false}、{@code slept_seconds=duration_seconds}（CC :185-190
     * 成功路径返回请求值）。
     *
     * <p>notify 等价（CC :121-128/:199-210 {@code notifyAutomationStateChanged}）：Java 无
     * proactive 自动化状态（PROACTIVE/KAIROS 未接线）→ N/A 登记（不实现等价调用）。
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        // [G31①] 对齐 CC :117 {@code const { duration_seconds } = input}（z.number，无上限）。
        double durationSeconds = input.has("duration_seconds")
            ? input.get("duration_seconds").asDouble(0) : 0;
        if (durationSeconds < 0) {
            return ToolResult.error(call.id(), "SleepTool: duration_seconds must be >= 0");
        }
        try {
            long start = System.currentTimeMillis();
            boolean interrupted = sleepInterruptibly(durationSeconds, ctx);
            long elapsedMs = System.currentTimeMillis() - start;
            // CC :187-189 成功返回 slept_seconds=duration_seconds；interrupt :192-197 返回
            // slept_seconds=Math.round(elapsed/1000)。整数值写 long（JSON "3" 非 "3.0"）。
            double sleptSeconds = interrupted ? Math.round(elapsedMs / 1000.0) : durationSeconds;
            var out = JSON.createObjectNode();
            if (sleptSeconds == Math.floor(sleptSeconds) && !Double.isInfinite(sleptSeconds)) {
                out.put("slept_seconds", (long) sleptSeconds);
            } else {
                out.put("slept_seconds", sleptSeconds);
            }
            out.put("interrupted", interrupted);
            if (interrupted) {
                log.info("SleepTool: 检测到新命令或用户中断, 提前唤醒. 已睡 {} s (请求 {} s), interrupted=true",
                    sleptSeconds, durationSeconds);
            } else {
                log.info("SleepTool: slept {} s (requested {})", sleptSeconds, durationSeconds);
            }
            return ToolResult.success(call.id(), out.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(call.id(), "SleepTool: interrupted");
        }
    }

    /**
     * CC SleepTool.ts:51-53 {@code shouldInterruptSleep() = !isProactiveSleepAllowed() ||
     * hasQueuedWakeSignal()}。Java 无 proactive feature（PROACTIVE/KAIROS 未接线，CC :35-43
     * {@code isProactiveSleepAllowed()} 无 feature 时恒 true）→ 只做 {@code hasQueuedWakeSignal}
     * 分支：{@code queue.hasCommandsInQueue()}（CC :46-49 + messageQueueManager.ts:104-106）。
     * 未注入队列（null）→ false（保持裸 sleep 原行为）。
     */
    private boolean shouldInterruptSleep() {
        NotificationQueue queue = this.notificationQueue;
        return queue != null && queue.hasCommandsInQueue();
    }

    /** [G31①] abort 状态查询 · 对齐 CC :169-172 {@code context.abortController.signal.aborted}。 */
    private static boolean isAborted(ToolUseContext ctx) {
        return ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled();
    }

    /**
     * 睡眠 + 500ms 队列轮询唤醒 + abort 打断 · 对齐 CC SleepTool.ts:130-183
     * （{@code setTimeout(finish, duration)} + {@code setInterval(wakeCheck, SLEEP_WAKE_CHECK_INTERVAL_MS)}
     * + {@code addEventListener('abort', onAbort, {once:true})} :173-175）。
     *
     * <p>入眠前先检查一次（对齐 CC :108-115 + :169-172，若 {@code shouldInterruptSleep()} 或已
     * abort 则立刻返回 {@code slept=0 + interrupted=true}）；随后每 {@link #WAKE_CHECK_INTERVAL_MS}
     * 检查队列 + abort；新命令 / 用户取消 → 提前返回。abort listener 在 finally 移除
     * （对齐 CC :136-146 cleanup {@code removeEventListener('abort', onAbort)}，防残留累积）。
     *
     * @return true = 提前中断（队列出现新命令 / 用户 abort）；false = 睡满全时长
     * @throws InterruptedException 外部线程 interrupt（保留裸 sleep 原语义）
     */
    private boolean sleepInterruptibly(double durationSeconds, ToolUseContext ctx) throws InterruptedException {
        if (shouldInterruptSleep() || isAborted(ctx)) {
            return true;   // 入眠前队列已非空 / 已 abort → 立即中断（对齐 CC :108-115/:169-172）
        }
        AbortController abortController = (ctx != null && ctx.abortController() != null)
            ? ctx.abortController() : AbortController.NOOP;
        // 注册 abort listener → 打断睡眠（对齐 CC :173-175 addEventListener('abort', onAbort, once)）
        AtomicBoolean aborted = new AtomicBoolean(false);
        Consumer<AbortController> onAbort = ac -> aborted.set(true);
        abortController.onCancel(onAbort);
        try {
            long start = System.currentTimeMillis();
            long totalMs = (long) (durationSeconds * 1000);
            long remaining;
            while ((remaining = totalMs - (System.currentTimeMillis() - start)) > 0) {
                if (aborted.get()) {
                    return true;   // 用户 abort → 中断（对齐 CC interrupt reject 'interrupted'）
                }
                Thread.sleep(Math.min(remaining, WAKE_CHECK_INTERVAL_MS));
                if (shouldInterruptSleep() || aborted.get()) {
                    return true;   // 新命令（含 cron 入队）/ abort 到达 → 提前唤醒，本轮立即继续
                }
            }
            return false;
        } finally {
            abortController.removeOnCancel(onAbort); // 对齐 CC :145 cleanup removeEventListener
        }
    }

    /**
     * tool_result 块 · 对齐 CC SleepTool.ts:91-103
     * {@code mapToolResultToToolResultBlockParam(content)}：
     * {@code interrupted ? 'Sleep interrupted after ${slept_seconds}s' : 'Slept for ${slept_seconds}s'}。
     * data 为 JSON 字符串（execute 产物），解析抽 slept_seconds/interrupted；失败回退原 data。
     * isError / 非 ToolResult → null（fail-loud 回退默认渲染器）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr) || isError) {
            if (log.isDebugEnabled()) {
                log.debug("[SleepTool] mapToToolResultBlockParam 跳过: isError 或非 ToolResult（fail-loud 路径回退默认渲染器）");
            }
            return null;
        }
        Object data = tr.data();
        String content = (data != null) ? renderSleepMessage(String.valueOf(data)) : "";
        if (log.isDebugEnabled()) {
            log.debug("[SleepTool] mapToToolResultBlockParam 生成 tool_result content='{}'（CC SleepTool.ts:91-103）", content);
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /** 从 JSON data 渲染 CC 人类消息；解析失败回退原 data 串（fail-loud 不吞）。 */
    private static String renderSleepMessage(String dataJson) {
        try {
            JsonNode node = JSON.readTree(dataJson);
            if (node != null && node.isObject() && node.has("interrupted")) {
                boolean interrupted = node.path("interrupted").asBoolean(false);
                JsonNode s = node.path("slept_seconds");
                String secs = (s != null && s.isNumber()) ? formatSeconds(s.asDouble()) : "?";
                return interrupted
                    ? "Sleep interrupted after " + secs + "s"
                    : "Slept for " + secs + "s";
            }
        } catch (Exception e) {
            if (LoggerFactory.getLogger(SleepTool.class).isDebugEnabled()) {
                LoggerFactory.getLogger(SleepTool.class).debug(
                    "[SleepTool] renderSleepMessage 解析失败，回退原 data: {}", e.getMessage());
            }
        }
        return dataJson;
    }

    /** 整数值显示为 "3"，否则 "1.5"（对齐 CC JS 模板插值 ${slept_seconds}）。 */
    private static String formatSeconds(double d) {
        return (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long) d) : String.valueOf(d);
    }
}