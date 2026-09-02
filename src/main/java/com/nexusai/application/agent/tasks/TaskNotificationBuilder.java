package com.nexusai.application.agent.tasks;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * task_notification XML 构建器 — 对齐 CC LocalShellTask.tsx:105-171 + framework.ts:274-289
 *
 * <h2>两种格式</h2>
 * <ul>
 *   <li><b>格式 A (framework.ts:274-289)</b>: enqueueTaskNotification — 6 TAG, 含 {@code <task-type>}</li>
 *   <li><b>格式 B (LocalShellTask.tsx:160-165)</b>: enqueueShellNotification — 5 TAG, <b>无</b> {@code <task-type>} — s13 主用</li>
 * </ul>
 *
 * <h2>STATUS 字段来源</h2>
 * <p>{@code <status>} tag 的值 <b>显式来自</b> {@link BackgroundTask#status()}{@code .getStatusString()} —
 * 两个 build 方法均使用此动态来源 (而非硬编码字符串)。
 *
 * <h2>SUMMARY 格式 (CC LocalShellTask.tsx:148-154)</h2>
 * <ul>
 *   <li>完成: {@code Background command "{desc}" completed (exit code 0)}</li>
 *   <li>失败: {@code Background command "{desc}" failed with exit code {code}}</li>
 *   <li>Kill: {@code Background command "{desc}" was stopped}</li>
 *
 * <p>注：CC messages.ts:5496-5512 {@code wrapCommandText(raw, origin)} 的 Java 等价物
 * 已内联于 LlmAgentLoop drain 注入侧（[S07] origin 三分支，drainAndInjectQueued :7158，
 *   方法区间 :7158-7410）；本类旧实现
 * wrapCommandText（引用已删 {@code NotificationQueue.Origin}）已于合并时删除（C4 裁决，
 * 0 调用方死代码）。channel 来源消息注入不可信警告由 LlmAgentLoop 内联承担（安全，NEW-9 △-14 修复）。
 */
public class TaskNotificationBuilder {

    private static final Logger log = LoggerFactory.getLogger(TaskNotificationBuilder.class);

    /** CC LocalShellTask.tsx:23 — BACKGROUND_BASH_SUMMARY_PREFIX */
    public static final String SUMMARY_PREFIX = "Background command ";

    /**
     * task-notification 注入前缀 · CC original: messages.ts:5502 wrapCommandText task-notification 分支
     * （{@code `A background agent completed a task:\n${raw}`}）。
     *
     * <p><b>[C4] mid-turn drain 与空闲路径共享</b>：LlmAgentLoop.drainAndInjectQueued（mid-turn）与
     * CronIdleExecutor.runOneAgentLoop（空闲路径）统一引用本常量，两路径注入文本字节一致
     * （此前 mid-turn 内联字符串、空闲路径缺失前缀 —— 对齐后 task-notification 注入统一带此前缀）。
     */
    public static final String TASK_NOTIFICATION_PREFIX = "A background agent completed a task:\n";

    // XML tag names — CC constants/xml.ts:28-34 全连字符 (OPD-TS-20 D1)
    //   TASK_ID_TAG='task-id' / TOOL_USE_ID_TAG='tool-use-id' / TASK_TYPE_TAG='task-type'
    //   OUTPUT_FILE_TAG='output-file' / STATUS_TAG='status' / SUMMARY_TAG='summary'
    //   (下划线标签 print.ts:2015-2060 连字符正则 match 全空 → SDK 事件失真)
    private static final String TAG_TASK_ID = "task-id";
    private static final String TAG_TOOL_USE_ID = "tool-use-id";
    private static final String TAG_TASK_TYPE = "task-type";
    private static final String TAG_OUTPUT_FILE = "output-file";
    private static final String TAG_STATUS = "status";
    private static final String TAG_SUMMARY = "summary";
    // CC constants/xml.ts:36-38 — WORKTREE_TAG / WORKTREE_PATH_TAG / WORKTREE_BRANCH_TAG
    //   (worktreePath/worktreeBranch 为 CC 原生 camelCase 标签, 勿改连字符)
    private static final String TAG_WORKTREE = "worktree";
    private static final String TAG_WORKTREE_PATH = "worktreePath";
    private static final String TAG_WORKTREE_BRANCH = "worktreeBranch";

    /**
     * 构建 enqueueShellNotification XML — 5 TAG (s13 主用格式)，无额外 detail。
     *
     * <p>CC LocalShellTask.tsx:160-165: TASK_ID, TOOL_USE_ID, OUTPUT_FILE, STATUS, SUMMARY
     * <b>不含 &lt;task_type&gt;</b>
     */
    public static String buildEnqueueShellNotification(BackgroundTask task, int exitCode) {
        return buildEnqueueShellNotification(task, exitCode, null);
    }

    /**
     * 构建 enqueueShellNotification XML — 5 TAG (s13 主用格式)，可选 detail 追加到 summary。
     *
     * <p>T1（size-watchdog）: {@code LocalBashTaskRunner} 输出文件超 5GB 被 SIGKILL 时，kill 消息
     * 并入 summary，使模型可见的完成通知直接说明"输出超 5GB 被杀"（对齐 CC prependStderr 的模型可见
     * 语义，ShellCommand.ts:318-322）。detail 为 null/空 → 输出与 2 参一致（零行为变化）。
     *
     * @param task     后台任务（status 驱动 status tag + summary 格式）
     * @param exitCode 进程退出码
     * @param detail   追加到 summary 的额外说明（null/空 → 不追加）
     */
    public static String buildEnqueueShellNotification(BackgroundTask task, int exitCode, @Nullable String detail) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        appendTag(xml, TAG_TASK_ID, task.id());
        if (task.toolUseId() != null) {
            appendTag(xml, TAG_TOOL_USE_ID, task.toolUseId());
        }
        appendTag(xml, TAG_OUTPUT_FILE, task.outputFile());
        appendTag(xml, TAG_STATUS, task.status().getStatusString());
        String summary = formatSummary(task, exitCode);
        if (detail != null && !detail.isBlank()) {
            summary = summary + "\n" + detail;
        }
        appendTag(xml, TAG_SUMMARY, summary);
        xml.append("</task-notification>");

        if (log.isDebugEnabled()) {
            log.debug("TaskNotificationBuilder.enqueueShellNotification: taskId={}, exitCode={}", task.id(), exitCode);
        }
        return xml.toString();
    }

    /**
     * 构建 async agent 终态通知 XML · 对齐 CC {@code enqueueAgentNotification}
     * (LocalAgentTask.tsx:197-262, 调用点 agentToolUtils.ts:624-637 completed /
     * :659-667 killed / :673-681 failed).
     *
     * <p>CC 真源格式 (LocalAgentTask.tsx:252-258, 已 sed -n cat -A 自验 —— <b>所有 tag 顶格</b>,
     * 无缩进):
     * <pre>
     * &lt;task-notification&gt;
     * &lt;task-id&gt;...&lt;/task-id&gt;
     * [&lt;tool-use-id&gt;...&lt;/tool-use-id&gt;]
     * &lt;output-file&gt;...&lt;/output-file&gt;
     * &lt;status&gt;completed|failed|killed&lt;/status&gt;
     * &lt;summary&gt;Agent "..." completed / failed: ... / was stopped&lt;/summary&gt;
     * [&lt;result&gt;finalMessage&lt;/result&gt;]  (completed finalMessage / killed partialResult, CC :249)
     * [&lt;usage&gt;&lt;total_tokens&gt;..&lt;/total_tokens&gt;&lt;tool_uses&gt;..&lt;/tool_uses&gt;
     *  &lt;duration_ms&gt;..&lt;/duration_ms&gt;&lt;/usage&gt;]  (仅 completed, CC :250; usage 子标签 CC 自身下划线, 勿改)
     * [&lt;worktree&gt;&lt;worktreePath&gt;..&lt;/worktreePath&gt;[&lt;worktreeBranch&gt;..&lt;/worktreeBranch&gt;]&lt;/worktree&gt;]
     *   (CC :251 worktreeSection — worktreePath 存在时; worktreeBranch 可空 → 省略子 tag)
     * &lt;/task-notification&gt;
     * </pre>
     *
     * <p><b>[FORK-02 返工]</b> 旧实现 2 空格缩进 (appendTag) + summary/result 手工 2 空格 → 顶格;
     * 新增 {@code <worktree>} 段 (CC :251 worktreeSection)。CC 真源该格式<b>全部 tag 原样拼接
     * (无 escapeXml)</b>，与 shell 格式 (LocalShellTask.tsx:164 仅 summary escapeXml) 不同 —
     * 严格对齐 CC 字节, 不做转义.
     *
     * <p>与 {@link #buildEnqueueShellNotification} 差异: shell 通知 SUMMARY 为
     * {@code Background command "..."}, agent 通知为 {@code Agent "..."} (CC LocalAgentTask.tsx:246);
     * 额外携带 result/usage/worktree 段. failed 路径 error 承载于 {@code result.summary()}
     * (CC :246 failed: ${error || 'Unknown error'}).
     *
     * @param task   BackgroundTask (status 驱动 status tag + summary 格式)
     * @param result AsyncAgentResult (summary = finalMessage/partialResult/error; usage/totalTokens
     *               /totalToolUseCount/totalDurationMs 供 usage 段; null = 无 result/usage 段)
     * @return agent 格式的 task_notification XML
     */
    public static String buildEnqueueAgentNotification(BackgroundTask task, AsyncAgentResult result) {
        return buildEnqueueAgentNotification(task, result, null, null);
    }

    /**
     * 构建 async agent 终态通知 XML（worktree 段变体）· 对齐 CC {@code enqueueAgentNotification}
     * 的 {@code worktreeSection}（LocalAgentTask.tsx:251 + getWorktreeResult agentToolUtils.ts:622）。
     *
     * <p><b>WHY（FORK-02）</b>: fork / isolation=worktree 子代理存活（保留）或 resume 复用的
     * worktree 路径必须透传给父 Agent —— 否则模型拿到「任务完成」却收不到产物路径，无法定位/审阅
     * 子代理在隔离副本里改了什么（CC cleanupWorktreeIfNeeded AgentTool.tsx:644-685 仅在保留时返回
     * {@code {worktreePath, worktreeBranch}}；remove 时返回空 → 无 worktree 段）。
     *
     * <p>worktreePath 为 null/空白 → 输出与 2 参一致（零行为变化），仅字节追加 worktree 段。
     *
     * @param task          BackgroundTask
     * @param result        AsyncAgentResult
     * @param worktreePath  保留的隔离 worktree 绝对路径（null/空白 → 无 worktree 段）
     * @param worktreeBranch worktree 分支（可空；worktreePath 为空时忽略）
     * @return agent 格式的 task_notification XML（可能含 worktree 段）
     */
    public static String buildEnqueueAgentNotification(BackgroundTask task, AsyncAgentResult result,
                                                       @Nullable String worktreePath,
                                                       @Nullable String worktreeBranch) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        // CC LocalAgentTask.tsx:253-256 — 顶格 tag + 原样拼接 (无 escapeXml)
        xml.append("<task-id>").append(task.id()).append("</task-id>\n");
        if (task.toolUseId() != null) {
            xml.append("<tool-use-id>").append(task.toolUseId()).append("</tool-use-id>\n");
        }
        xml.append("<output-file>").append(task.outputFile()).append("</output-file>\n");
        xml.append("<status>").append(task.status().getStatusString()).append("</status>\n");
        // CC LocalAgentTask.tsx:246-257: summary/result 是模板字面量原样拼接 (无 escapeXml),
        //   与 shell 格式 (LocalShellTask.tsx:164 escapeXml) 不同 — 严格对齐 CC 字节, 不做转义.
        xml.append("<summary>").append(formatAgentSummary(task, result)).append("</summary>\n");
        // CC LocalAgentTask.tsx:249 resultSection — completed finalMessage / killed partialResult;
        //   failed 的 error 已并入 summary, 不重复 result 段.
        if (result != null && result.summary() != null && !result.summary().isBlank()
                && task.status() != BackgroundTaskStatus.FAILED) {
            xml.append("<result>").append(result.summary()).append("</result>\n");
        }
        // CC LocalAgentTask.tsx:250 usageSection — 仅 completed 携带 usage
        //   (agentToolUtils.ts:630-634 usage{totalTokens, toolUses, durationMs}).
        if (result != null && task.status() == BackgroundTaskStatus.COMPLETED) {
            xml.append("<usage><total_tokens>").append(result.totalTokens())
               .append("</total_tokens><tool_uses>").append(result.totalToolUseCount())
               .append("</tool_uses><duration_ms>").append(result.totalDurationMs())
               .append("</duration_ms></usage>\n");
        }
        // CC LocalAgentTask.tsx:251 worktreeSection — worktreePath 存在时:
        //   `\n<worktree><worktreePath>${worktreePath}</worktreePath>${worktreeBranch ? `<worktreeBranch>...` : ''}</worktree>`
        //   单一闭合 tag 无换行分隔; 子 tag 名 worktreePath/worktreeBranch 为 CC camelCase 原生.
        if (worktreePath != null && !worktreePath.isBlank()) {
            xml.append("<worktree><worktreePath>").append(worktreePath).append("</worktreePath>");
            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                xml.append("<worktreeBranch>").append(worktreeBranch).append("</worktreeBranch>");
            }
            xml.append("</worktree>\n");
        }
        xml.append("</task-notification>");

        if (log.isDebugEnabled()) {
            log.debug("TaskNotificationBuilder.enqueueAgentNotification: taskId={}, status={}, "
                    + "worktreePath={}, worktreeBranch={}",
                task.id(), task.status().getStatusString(), worktreePath, worktreeBranch);
        }
        return xml.toString();
    }

    /**
     * Agent 通知 SUMMARY 格式 · CC LocalAgentTask.tsx:246
     * <pre>
     * completed: Agent "{desc}" completed
     * failed:    Agent "{desc}" failed: {error|'Unknown error'}
     * killed:    Agent "{desc}" was stopped
     * </pre>
     */
    private static String formatAgentSummary(BackgroundTask task, AsyncAgentResult result) {
        return switch (task.status()) {
            case COMPLETED -> "Agent \"" + task.description() + "\" completed";
            case FAILED -> "Agent \"" + task.description() + "\" failed: "
                + (result != null && result.summary() != null && !result.summary().isBlank()
                    ? result.summary() : "Unknown error");
            case KILLED -> "Agent \"" + task.description() + "\" was stopped";
            default -> "Agent \"" + task.description() + "\" status: " + task.status().getStatusString();
        };
    }

    /**
     * 构建 enqueueTaskNotification XML — 6 TAG (framework 层格式)
     *
     * <p>CC framework.ts:274-289: TASK_ID, TOOL_USE_ID, TASK_TYPE, OUTPUT_FILE, STATUS, SUMMARY
     */
    public static String buildEnqueueTaskNotification(BackgroundTask task) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        appendTag(xml, TAG_TASK_ID, task.id());
        if (task.toolUseId() != null) {
            appendTag(xml, TAG_TOOL_USE_ID, task.toolUseId());
        }
        appendTag(xml, TAG_TASK_TYPE, task.type().getTypeString()); // ← Format A only
        appendTag(xml, TAG_OUTPUT_FILE, task.outputFile());
        appendTag(xml, TAG_STATUS, task.status().getStatusString());
        appendTag(xml, TAG_SUMMARY, formatFrameworkSummary(task));
        xml.append("</task-notification>");

        if (log.isDebugEnabled()) {
            log.debug("TaskNotificationBuilder.enqueueTaskNotification: taskId={}", task.id());
        }
        return xml.toString();
    }

    /**
     * SUMMARY 格式 — CC LocalShellTask.tsx:148-154
     * <pre>
     * completed: "Background command \"{desc}\" completed (exit code {code})"
     * failed:    "Background command \"{desc}\" failed with exit code {code}"
     * killed:    "Background command \"{desc}\" was stopped"
     * </pre>
     */
    private static String formatSummary(BackgroundTask task, int exitCode) {
        return switch (task.status()) {
            case COMPLETED -> SUMMARY_PREFIX + "\"" + task.description() + "\" completed (exit code " + exitCode + ")";
            case FAILED -> SUMMARY_PREFIX + "\"" + task.description() + "\" failed with exit code " + exitCode;
            case KILLED -> SUMMARY_PREFIX + "\"" + task.description() + "\" was stopped";
            default -> SUMMARY_PREFIX + "\"" + task.description() + "\" status: " + task.status().getStatusString();
        };
    }

    /**
     * Framework 格式 Summary — CC framework.ts:274-289 + getStatusText (framework.ts:295-304).
     * <pre>
     * completed → Task "..." completed successfully
     * failed    → Task "..." failed          (旧实现恒拼 " successfully" 出 "failed successfully" 语义错)
     * killed    → Task "..." was stopped
     * </pre>
     */
    private static String formatFrameworkSummary(BackgroundTask task) {
        String statusText = switch (task.status()) {
            case COMPLETED -> "completed successfully";
            case FAILED -> "failed";
            case KILLED -> "was stopped";
            case RUNNING -> "is running";
            case PENDING -> "is pending";
        };
        return "Task \"" + task.description() + "\" " + statusText;
    }

    /**
     * 追加单行 tag · 对齐 CC 全部 task_notification 格式 (LocalShellTask.tsx:160-165 /
     * framework.ts:283-289 / LocalMainSessionTask.ts:254-260) — <b>顶格无缩进</b>.
     *
     * <p>[FORK-02 返工] 旧实现前置 2 空格 → 顶格 (CC 真源模板字面量 tag 全部顶格; drain 侧
     * LlmAgentLoop :7467-7485 子串判定 + print.ts 正则解析均与缩进无关). shell 格式 summary 保留
     * escapeXml (CC LocalShellTask.tsx:164 仅 summary 转义), 其余 tag 维持既有 escapeXml 行为
     * (agent 格式改走 {@link #buildEnqueueAgentNotification} 内联原样拼接).
     */
    private static void appendTag(StringBuilder sb, String tag, String value) {
        sb.append("<").append(tag).append(">")
          .append(escapeXml(value))
          .append("</").append(tag).append(">\n");
    }

    /**
     * 构建主会话后台化完成通知 XML · 对齐 CC {@code enqueueMainSessionNotification}
     * (LocalMainSessionTask.ts:224-263, 调用点 completeMainSessionTask :200-206).
     *
     * <p>CC 真源格式 (:255-260, 已 sed -n 254-261 cat -A 自验):
     * <pre>
     * &lt;task-notification&gt;
     * &lt;task-id&gt;...&lt;/task-id&gt;${toolUseIdLine}
     * &lt;output-file&gt;...&lt;/output-file&gt;
     * &lt;status&gt;completed|failed&lt;/status&gt;
     * &lt;summary&gt;Background session "..." completed|failed&lt;/summary&gt;
     * &lt;/task-notification&gt;
     * </pre>
     * 5-6 TAG: task-id + [tool-use-id] + output-file + status + summary, <b>无 {@code task-type}</b>
     * (CC :255-260 未含 TASK_TYPE_TAG). summary 用模板字面量原样拼接 (无 escapeXml,
     * CC :260) — 与 shell 格式 (LocalShellTask.tsx:164 escapeXml) 不同, 严格对齐 CC 字节.
     *
     * <p>与 MainSessionBackgroundService.enqueueMainSessionNotification 内联版
     * (该 service :446-456) 输出字节一致 (2 空格缩进, 不转义), 使接线零行为差.
     *
     * @param taskId      主会话后台化任务 id ('s' 前缀)
     * @param description 任务描述 ('Background session')
     * @param status      终态 'completed' | 'failed'
     * @param toolUseId   关联 tool_use id (可空, 空 → 无 tool-use-id 行)
     * @param outputFile  任务输出文件路径 (可空, 空 → 空 output-file)
     * @return task_notification XML (mode=task-notification)
     */
    public static String buildMainSessionNotification(String taskId, String description,
                                                      String status, String toolUseId,
                                                      String outputFile) {
        String summary = "completed".equals(status)
            ? "Background session \"" + description + "\" completed"
            : "Background session \"" + description + "\" failed";
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        xml.append("  <task-id>").append(taskId).append("</task-id>\n");
        if (toolUseId != null && !toolUseId.isBlank()) {
            xml.append("  <tool-use-id>").append(toolUseId).append("</tool-use-id>\n");
        }
        xml.append("  <output-file>").append(outputFile != null ? outputFile : "").append("</output-file>\n");
        xml.append("  <status>").append(status).append("</status>\n");
        xml.append("  <summary>").append(summary).append("</summary>\n");
        xml.append("</task-notification>");

        if (log.isDebugEnabled()) {
            log.debug("TaskNotificationBuilder.buildMainSessionNotification: taskId={}, status={}",
                taskId, status);
        }
        return xml.toString();
    }

    /**
     * 构建 stall 通知 XML — CC LocalShellTask.tsx:76-94
     *
     * <p>s13 P1-5 修复: 严格按 CC 格式 — <b>无 {@code <status>} tag</b>
     * (CC LocalShellTask.tsx:76-79 注释: 有 status 会被 print.ts 当终态信号误关任务).
     *
     * <p>CC 格式:
     * <pre>
     * &lt;task-notification&gt;
     *   &lt;task-id&gt;...&lt;/task-id&gt;
     *   &lt;tool-use-id&gt;...&lt;/tool-use-id&gt;  (optional)
     *   &lt;output-file&gt;...&lt;/output-file&gt;
     *   &lt;summary&gt;Background command "..." appears to be waiting for input.\nLast output:\n{tail}\n\nThe command is likely blocked on an interactive prompt. Kill this task and re-run with piped input (e.g., `echo y | command`) or a non-interactive flag.&lt;/summary&gt;
     * &lt;/task-notification&gt;
     * </pre>
     *
     * <p>Advisory 性质: 任务继续存活 (kill 与否是模型决策).
     */
    public static String buildStallNotification(String taskId, String description, String lastOutput) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<task-notification>\n");
        appendTag(xml, TAG_TASK_ID, taskId);
        // CC: 故意省略 <status> tag (避免 print.ts 误判终态)
        appendTag(xml, TAG_SUMMARY,
            "Background command \"" + description + "\" appears to be waiting for input.\n"
            + "Last output:\n" + lastOutput + "\n\n"
            + "The command is likely blocked on an interactive prompt. "
            + "Kill this task and re-run with piped input "
            + "(e.g., `echo y | command`) or a non-interactive flag");
        xml.append("</task-notification>");
        return xml.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
