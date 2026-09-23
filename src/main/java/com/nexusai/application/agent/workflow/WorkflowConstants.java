package com.nexusai.application.agent.workflow;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolNameConstants;

import java.util.List;

/**
 * 引擎级常量 · 对齐 CC {@code packages/workflow-engine/src/constants.ts}（全 33 行）。
 *
 * <p>W-1c 实施（Execute-W1c）期间创建的最小 W-1a 支撑集，供引擎自包含编译与单测。
 * 字段逐一 JavaDoc 标注 CC 原名 + 行号（对齐 CC 经验规则 7）。</p>
 *
 * <p><b>单一权威声明</b>：{@link #WORKFLOW_TOOL_NAME} 引用
 * {@link ToolNameConstants#WORKFLOW_TOOL_NAME}（agent/tool/ToolNameConstants.java:162，CC original: WORKFLOW_TOOL_NAME
 * constants.ts:7），不重复造字面量。</p>
 */
public final class WorkflowConstants {

    private WorkflowConstants() {
    }

    /** CC original: WORKFLOW_TOOL_NAME (constants.ts:7) — 引用 ToolNameConstants 单一权威。 */
    public static final String WORKFLOW_TOOL_NAME = ToolNameConstants.WORKFLOW_TOOL_NAME;

    /**
     * CC original: WORKFLOW_DIR_NAME (constants.ts:10) — 用户命名 workflow 文件目录。
     *
     * <p>决策 D6/D7 目录迁移收口：项目级 nexusai 目录全动态（appName=nexusai →
     * {@code .nexusai/workflows}），nexusai 优先；既有 {@code .claude/workflows} 目录作读取
     * 回落源（见 {@link NamedWorkflows#resolveWithFallback} / {@link NamedWorkflows#listWithFallback}）。</p>
     *
     * <p><b>[批 appname-dyn 追加 2026-09-23] 本常量是「源字面量」（appName=nexusai 形态），
     * ⛔ 不再是运行期真值</b>：原实现直接写 {@code NexusaiPaths.getProjectDirName() + "/workflows"}
     * —— {@code static final} 在<b>类加载期</b>冻结，早于 {@code NexusaiAppNameInitializer} 的
     * {@code @PostConstruct} 注入 appName（时序纪律同 {@code StatuslineCommand#ALLOWED_TOOLS}）
     * ⇒ appName≠nexusai 时会被冻成默认名、目录错位。故本常量保留默认字面（保 javadoc 锚点 +
     * 既有测试锚），<b>生产取值一律走 {@link #workflowDirName()}</b>（运行期现读）。
     * appName=nexusai ⇒ 两者逐字节相同（主线零变化）。</p>
     */
    public static final String WORKFLOW_DIR_NAME = ".nexusai/workflows";

    /**
     * CC original: WORKFLOW_RUNS_DIR (constants.ts:13) — workflow 运行持久化目录（journal + run records）。
     *
     * <p>决策 D6/D7 目录迁移收口：同 {@link #WORKFLOW_DIR_NAME}（appName=nexusai →
     * {@code .nexusai/workflow-runs}）。</p>
     *
     * <p><b>[批 appname-dyn 追加 2026-09-23] 本常量同为「源字面量」</b>：消时序冻结的处置同
     * {@link #WORKFLOW_DIR_NAME}（原 {@code NexusaiPaths.getProjectDirName() + "/workflow-runs"}
     * 在类加载期冻结）；生产取值走 {@link #workflowRunsDir()}。</p>
     */
    public static final String WORKFLOW_RUNS_DIR = ".nexusai/workflow-runs";

    /**
     * workflow 目录名的<b>运行期形态</b> · 每次现读 appName
     * （{@code NexusaiPaths.getProjectDirName() + "/workflows"}，决策 D6/D7）。
     *
     * <p><b>WHY 是方法不是常量</b>：见 {@link #WORKFLOW_DIR_NAME} 的时序说明 ——
     * {@code static final} 冻结早于 appName 注入。本方法是<b>唯一生产取值口</b>。
     *
     * @return 与当前 appName 联动的 workflow 目录名（appName=nexusai ⇒ {@code .nexusai/workflows}）
     */
    public static String workflowDirName() {
        return NexusaiPaths.getProjectDirName() + "/workflows";
    }

    /**
     * workflow 运行持久化目录名的<b>运行期形态</b> · 每次现读 appName
     * （{@code NexusaiPaths.getProjectDirName() + "/workflow-runs"}）。
     *
     * @return 与当前 appName 联动的 workflow-runs 目录名（appName=nexusai ⇒ {@code .nexusai/workflow-runs}）
     */
    public static String workflowRunsDir() {
        return NexusaiPaths.getProjectDirName() + "/workflow-runs";
    }

    /** CC original: WORKFLOW_SCRIPT_EXTENSIONS (constants.ts:16) — 命名 workflow 支持扩展名（优先级序）。 */
    public static final List<String> WORKFLOW_SCRIPT_EXTENSIONS = List.of(".ts", ".js", ".mjs");

    /** CC original: DEFAULT_MAX_CONCURRENCY (constants.ts:23) — 每次 workflow run 默认信号量许可数。 */
    public static final int DEFAULT_MAX_CONCURRENCY = 3;

    /** CC original: MAX_CONCURRENCY_CAP (constants.ts:26) — 用户 maxConcurrency 绝对上限（防滥用）。 */
    public static final int MAX_CONCURRENCY_CAP = 16;

    /** CC original: MAX_TOTAL_AGENTS (constants.ts:29) — 单 workflow 生命周期内 agent() 调用总数上限。 */
    public static final int MAX_TOTAL_AGENTS = 1000;

    /** CC original: MAX_ITEMS_PER_CALL (constants.ts:32) — 单次 parallel()/pipeline() 调用条目上限。 */
    public static final int MAX_ITEMS_PER_CALL = 4096;
}
