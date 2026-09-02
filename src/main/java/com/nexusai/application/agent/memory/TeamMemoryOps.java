package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import java.util.List;

/**
 * TeamMemoryOps · 对齐 CC {@code Open-ClaudeCode/src/utils/teamMemoryOps.ts}（88L，无尾部换行）。
 *
 * <p><b>CC 真源（2026-08-17 grep/read 自验）</b>：三函数 + O1 re-export：
 * <ul>
 *   <li>{@code isTeamMemFile} re-export（:5）—— Java 等价已由 {@link TeamMemPaths#isTeamMemFile}
 *       承担（MemoryFileDetection.isTeamMemFile 委托同源），不重复实现</li>
 *   <li>{@code isTeamMemorySearch}（:10-21）：仅检查 {@code input.path}（不查 pattern/glob）；
 *       input 为空 → false；path 命中 team memory 文件 → true</li>
 *   <li>{@code isTeamMemoryWriteOrEdit}（:26-36）：仅 FILE_WRITE_TOOL_NAME('Write') /
 *       FILE_EDIT_TOOL_NAME('Edit')（CC FileWriteTool/prompt.ts:3 + FileEditTool/constants.ts:2，
 *       与 {@link ToolNameConstants} 一致）；{@code file_path ?? path}（nullish：file_path 存在即优先，
 *       含空串，不回落）；filePath 未定义 → false</li>
 *   <li>{@code appendTeamMemorySummaryParts}（:42-88）：三计数 {@code ?? 0}；每段按
 *       {@code parts.length === 0}（实时判断，先 push 的段使后续段转小写）+ isActive 决定
 *       动词（Recalling/recalling/Recalled/recalled · Searching/searching/Searched/searched ·
 *       Writing/writing/Wrote/wrote）；read/write 段含计数与单复数 memory/memories，
 *       search 段固定 {@code "team memories"}（无计数）；顺序 read → search → write</li>
 * </ul>
 *
 * <p><b>IMP-MV2-23（✗-O2/O3/O4 补齐）</b>：CC 消费方全在 CLI 折叠渲染链
 * {@code collapseReadSearch.ts:791-796/:845-849/:864-868/:1016-1019}（teamMemoryWriteCount /
 * teamMemorySearchCount / teamMemoryReadFilePaths / getSearchReadSummaryText 摘要）；Java 无同构
 * 渲染层（TeammateMessageFoldingChain 仅 teammate_shutdown 折叠，ChatController GET /messages
 * 消费；无 collapseReadSearchGroups 步骤）—— 本类按 Web 形态补概念实现（03 §8.1 X1 补齐路径），
 * 消费点接线见 progress/IMP-MV2-23.md 显式登记。摘要动词（Recalling/Searched/Wrote team …）为
 * 前端职责边界声明。
 *
 * <p>输入形态：工具输入用 JsonNode（与 SessionFileAccessHooks 同款）。畸形输入（非对象/非文本
 * 字段）fail-safe 返回 false，不抛异常（CC 运行时对畸形输入抛 TypeError，Java 侧防御收敛）。
 */
public final class TeamMemoryOps {

    private final TeamMemPaths teamMemPaths;

    public TeamMemoryOps(TeamMemPaths teamMemPaths) {
        this.teamMemPaths = teamMemPaths;
    }

    /**
     * 搜索工具调用是否针对 team memory 文件 · CC original: {@code isTeamMemorySearch}
     * （teamMemoryOps.ts:10-21）。仅检查 {@code input.path}（CC :12-19 不检查 pattern/glob）。
     *
     * @param toolInput 搜索工具输入（Grep/Glob 等；path 字段为目标目录/文件）
     * @return true = 输入含 path 且命中 team memory 文件（team 启用时）
     */
    public boolean isTeamMemorySearch(JsonNode toolInput) {
        if (toolInput == null || !toolInput.isObject()) {
            return false;
        }
        JsonNode pathNode = toolInput.get("path");
        if (pathNode == null || pathNode.isNull() || !pathNode.isTextual()) {
            return false;
        }
        String path = pathNode.asText();
        // CC :18 `input.path && isTeamMemFile(input.path)` —— JS truthy：空串视为无 path
        if (path.isEmpty()) {
            return false;
        }
        return teamMemPaths.isTeamMemFile(path);
    }

    /**
     * Write/Edit 工具调用是否针对 team memory 文件 · CC original: {@code isTeamMemoryWriteOrEdit}
     * （teamMemoryOps.ts:26-36）。{@code file_path ?? path}（CC :33）：file_path 存在（含空串）即
     * 优先，否则回落 path；两者皆缺 → false。
     *
     * @param toolName  工具名（仅 'Write'/'Edit' 进入判定，CC :28-30）
     * @param toolInput 工具输入
     * @return true = Write/Edit 且目标路径命中 team memory 文件
     */
    public boolean isTeamMemoryWriteOrEdit(String toolName, JsonNode toolInput) {
        if (!ToolNameConstants.FILE_WRITE_TOOL_NAME.equals(toolName)
            && !ToolNameConstants.FILE_EDIT_TOOL_NAME.equals(toolName)) {
            return false;
        }
        if (toolInput == null || !toolInput.isObject()) {
            return false;
        }
        JsonNode filePathNode = toolInput.get("file_path");
        JsonNode pathNode = toolInput.get("path");
        String filePath;
        if (filePathNode != null && !filePathNode.isNull() && filePathNode.isTextual()) {
            filePath = filePathNode.asText();
        } else if (pathNode != null && !pathNode.isNull() && pathNode.isTextual()) {
            filePath = pathNode.asText();
        } else {
            return false;
        }
        // CC :35 `filePath !== undefined && isTeamMemFile(filePath)` —— 空串也进入判定
        // （isTeamMemPath 空串 resolve → cwd，不在 team 目录 → false，两端等价）
        return teamMemPaths.isTeamMemFile(filePath);
    }

    /**
     * 向摘要 parts 追加 team memory 操作统计 · CC original: {@code appendTeamMemorySummaryParts}
     * （teamMemoryOps.ts:42-88）。封装全部 team 动词/字符串逻辑（getSearchReadSummaryText 消费）。
     *
     * <p>行为细节（逐行对齐 CC）：
     * <ul>
     *   <li>三计数缺省 0（CC {@code ?? 0}）</li>
     *   <li>read/write 段：{@code "<verb> <n> team memory|memories"}（n==1 单数）；search 段：
     *       {@code "<verb> team memories"}（<b>无计数</b>，CC :63-64）</li>
     *   <li>动词 = isActive ? 进行时（Recalling/Searching/Writing）: 完成时（Recalled/Searched/Wrote）；
     *       parts 空（首段）大写，非空（后续段）小写 —— {@code parts.length === 0} 每段实时判定</li>
     *   <li>push 顺序 read → search → write（CC :47-87）</li>
     * </ul>
     *
     * @param counts   team memory 三计数（缺省 0 语义由调用方传 0 或 {@link TeamMemoryCounts#of}）
     * @param isActive 当前会话是否活跃（CC isActive → 进行时动词）
     * @param parts    摘要片段列表（就地追加）
     */
    public void appendTeamMemorySummaryParts(TeamMemoryCounts counts, boolean isActive, List<String> parts) {
        if (counts == null || parts == null) {
            return;
        }
        int teamReadCount = counts.teamMemoryReadCount();
        int teamSearchCount = counts.teamMemorySearchCount();
        int teamWriteCount = counts.teamMemoryWriteCount();
        if (teamReadCount > 0) {
            String verb = verb("Recalling", "recalling", "Recalled", "recalled", isActive, parts);
            parts.add(verb + " " + teamReadCount + " team "
                + (teamReadCount == 1 ? "memory" : "memories"));
        }
        if (teamSearchCount > 0) {
            String verb = verb("Searching", "searching", "Searched", "searched", isActive, parts);
            parts.add(verb + " team memories");
        }
        if (teamWriteCount > 0) {
            String verb = verb("Writing", "writing", "Wrote", "wrote", isActive, parts);
            parts.add(verb + " " + teamWriteCount + " team "
                + (teamWriteCount == 1 ? "memory" : "memories"));
        }
    }

    /**
     * team memory 三计数 · CC {@code {teamMemoryReadCount?, teamMemorySearchCount?, teamMemoryWriteCount?}}
     * （teamMemoryOps.ts:43-45 可选字段，缺省 0）。{@link #of} 提供 null→0 归一（CC {@code ?? 0} 语义）。
     */
    public record TeamMemoryCounts(int teamMemoryReadCount,
                                   int teamMemorySearchCount,
                                   int teamMemoryWriteCount) {

        /** CC {@code ?? 0} 归一：null 计数按 0 处理。 */
        public static TeamMemoryCounts of(Integer read, Integer search, Integer write) {
            return new TeamMemoryCounts(
                read == null ? 0 : read,
                search == null ? 0 : search,
                write == null ? 0 : write);
        }
    }

    /**
     * 动词选择 · CC :48-52/:60-64/:72-76 三组同构：isActive → 进行时，否则完成时；
     * {@code parts.length === 0}（首段）→ 大写，否则小写。
     */
    private static String verb(String activeFirst, String activeLater,
                               String pastFirst, String pastLater,
                               boolean isActive, List<String> parts) {
        if (isActive) {
            return parts.isEmpty() ? activeFirst : activeLater;
        }
        return parts.isEmpty() ? pastFirst : pastLater;
    }
}
