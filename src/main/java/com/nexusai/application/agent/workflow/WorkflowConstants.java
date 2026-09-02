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
     * <p>决策 D6/D7 目录迁移收口：项目级 nexusai 目录全动态
     * {@code NexusaiPaths.getProjectDirName() + "/workflows"}（appName=nexusai → {@code .nexusai/workflows}），
     * nexusai 优先；既有 {@code .claude/workflows} 目录作读取回落源（见
     * {@link NamedWorkflows#resolveWithFallback} / {@link NamedWorkflows#listWithFallback}）。</p>
     */
    public static final String WORKFLOW_DIR_NAME = NexusaiPaths.getProjectDirName() + "/workflows";

    /**
     * CC original: WORKFLOW_RUNS_DIR (constants.ts:13) — workflow 运行持久化目录（journal + run records）。
     *
     * <p>决策 D6/D7 目录迁移收口：同 {@link #WORKFLOW_DIR_NAME} 随 appName 动态
     * （appName=nexusai → {@code .nexusai/workflow-runs}）。</p>
     */
    public static final String WORKFLOW_RUNS_DIR = NexusaiPaths.getProjectDirName() + "/workflow-runs";

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
