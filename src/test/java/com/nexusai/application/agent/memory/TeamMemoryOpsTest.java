package com.nexusai.application.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TeamMemoryOps 三函数行为测试 · 对齐 CC {@code utils/teamMemoryOps.ts:10-21/:26-36/:42-88}
 * （IMP-MV2-23 验证命令：新增单测，三函数行为对齐 CC 真源）。
 *
 * <p>行为基准（CC 真源 read 自验，不信注释）：
 * <ul>
 *   <li>{@code isTeamMemorySearch}（:10-21）：仅检查 {@code input.path}（不查 pattern/glob）；
 *       input 为空 → false；path 空串（JS falsy）→ false；path 命中 team 文件 → true</li>
 *   <li>{@code isTeamMemoryWriteOrEdit}（:26-36）：仅 'Write'/'Edit'；{@code file_path ?? path}
 *       （file_path 存在即优先，含空串；null 回落 path）；filePath undefined → false</li>
 *   <li>{@code appendTeamMemorySummaryParts}（:42-88）：三计数 ?? 0；read/write 带计数+单复数、
 *       search 固定 "team memories" 无计数；isActive 决定进行时/完成时；parts 空首段大写；
 *       顺序 read → search → write</li>
 * </ul>
 */
class TeamMemoryOpsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TeamMemPaths paths;
    private TeamMemoryOps ops;

    @BeforeEach
    void setUp() {
        // 同 AutoMemPathsTest.teamPaths 门控形态：auto-memory=true + feature('TEAMMEM')=true +
        // tengu_herring_clock=true → team 分支全开
        paths = new TeamMemPaths(
            new AutoMemPaths(() -> "C:/proj", () -> "C:/mem", () -> null, () -> null),
            () -> true, () -> true, () -> true);
        ops = new TeamMemoryOps(paths);
    }

    private JsonNode obj(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("测试 JSON 非法: " + json, e);
        }
    }

    /** 路径嵌入 JSON 字符串前转义反斜杠（Windows 路径：C:\mem\... → \\）。 */
    private static String jsonPath(String path) {
        return path.replace("\\", "\\\\");
    }
    // ════════════════════════════════════════════════════════════════
    // isTeamMemorySearch · teamMemoryOps.ts:10-21
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeamMemorySearch：null / 非对象输入 → false（CC :12-15）")
    void search_nullInput_false() {
        assertThat(ops.isTeamMemorySearch(null)).isFalse();
        assertThat(ops.isTeamMemorySearch(obj("\"str\""))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemorySearch：path 命中 team memory 文件 → true（CC :18-19）")
    void search_pathInTeamDir_true() {
        String teamFile = paths.getTeamMemPath() + "MEMORY.md";
        assertThat(ops.isTeamMemorySearch(obj("{\"path\": \"" + jsonPath(teamFile) + "\"}"))).isTrue();
    }

    @Test
    @DisplayName("isTeamMemorySearch：path 非 team 文件 → false")
    void search_pathOutsideTeam_false() {
        assertThat(ops.isTeamMemorySearch(obj("{\"path\": \"C:/mem/MEMORY.md\"}"))).isFalse();
        assertThat(ops.isTeamMemorySearch(obj("{\"path\": \"C:/proj/src/A.java\"}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemorySearch：无 path（仅 pattern/glob）→ false（CC 只查 path，:12）")
    void search_noPathOnlyPattern_false() {
        assertThat(ops.isTeamMemorySearch(obj("{\"pattern\": \"**/*.md\"}"))).isFalse();
        assertThat(ops.isTeamMemorySearch(obj("{\"glob\": \"**/*.md\"}"))).isFalse();
        assertThat(ops.isTeamMemorySearch(obj("{}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemorySearch：path 空串（JS falsy）→ false（CC :18 truthy 检查）")
    void search_emptyPath_false() {
        assertThat(ops.isTeamMemorySearch(obj("{\"path\": \"\"}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemorySearch：runtime 关（tengu_herring_clock=false）→ false（isTeamMemFile 含 isTeamMemoryEnabled）")
    void search_runtimeOff_false() {
        // CC isTeamMemFile（teamMemPaths.ts:290-292）= isTeamMemoryEnabled() && isTeamMemPath()，
        // isTeamMemoryEnabled（:73-78）= isAutoMemoryEnabled() && tengu_herring_clock —— feature('TEAMMEM')
        // 编译门不在函数内，由消费点组合（collapseReadSearch.ts:791/:845/:865 `feature('TEAMMEM') &&`）；
        // 函数级门控 = runtime/auto 双门。
        TeamMemPaths off = new TeamMemPaths(
            new AutoMemPaths(() -> "C:/proj", () -> "C:/mem", () -> null, () -> null),
            () -> true, () -> true, () -> false);
        TeamMemoryOps offOps = new TeamMemoryOps(off);
        String teamFile = off.getTeamMemPath() + "MEMORY.md";
        assertThat(offOps.isTeamMemorySearch(obj("{\"path\": \"" + jsonPath(teamFile) + "\"}"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // isTeamMemoryWriteOrEdit · teamMemoryOps.ts:26-36
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：非 Write/Edit 工具名 → false（CC :28-30）")
    void writeOrEdit_nonWriteEditTool_false() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Read", obj("{\"file_path\": \"" + jsonPath(teamFile()) + "\"}"))).isFalse();
        assertThat(ops.isTeamMemoryWriteOrEdit("Grep", obj("{\"path\": \"" + jsonPath(teamFile()) + "\"}"))).isFalse();
        assertThat(ops.isTeamMemoryWriteOrEdit("Bash", obj("{}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：Write + file_path 命中 → true（CC :33-35）")
    void writeOrEdit_writeFilePathInTeam_true() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write", obj("{\"file_path\": \"" + jsonPath(teamFile()) + "\"}"))).isTrue();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：Edit + file_path 命中 → true")
    void writeOrEdit_editFilePathInTeam_true() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Edit", obj("{\"file_path\": \"" + jsonPath(teamFile()) + "\"}"))).isTrue();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：file_path 缺失 → 回落 path（CC ?? 语义）")
    void writeOrEdit_fallbackToPath_true() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write", obj("{\"path\": \"" + jsonPath(teamFile()) + "\"}"))).isTrue();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：file_path 为 JSON null → 回落 path")
    void writeOrEdit_nullFilePathFallbackToPath_true() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write",
            obj("{\"file_path\": null, \"path\": \"" + jsonPath(teamFile()) + "\"}"))).isTrue();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：file_path 存在（非 team）优先，不回落 path（CC ?? 不回退非 undefined）")
    void writeOrEdit_nonTeamFilePathWins_false() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write",
            obj("{\"file_path\": \"C:/proj/src/A.java\", \"path\": \"" + jsonPath(teamFile()) + "\"}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：file_path 空串优先（CC ?? 不回退空串）→ isTeamMemFile(\"\")=false")
    void writeOrEdit_emptyFilePathWins_false() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write",
            obj("{\"file_path\": \"\", \"path\": \"" + jsonPath(teamFile()) + "\"}"))).isFalse();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：无 file_path/path → false（CC :34-35 undefined）")
    void writeOrEdit_noPath_false() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Write", obj("{}"))).isFalse();
        assertThat(ops.isTeamMemoryWriteOrEdit("Write", null)).isFalse();
    }

    @Test
    @DisplayName("isTeamMemoryWriteOrEdit：file_path 非 team 文件 → false")
    void writeOrEdit_filePathOutsideTeam_false() {
        assertThat(ops.isTeamMemoryWriteOrEdit("Edit", obj("{\"file_path\": \"C:/mem/MEMORY.md\"}"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // appendTeamMemorySummaryParts · teamMemoryOps.ts:42-88
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("appendTeamMemorySummaryParts：全 0 计数 → 不追加（CC :47-50 ?? 0）")
    void append_allZero_noParts() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(0, 0, 0), true, parts);
        assertThat(parts).isEmpty();
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：null counts 防御 → 不追加")
    void append_nullCounts_noParts() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(null, true, parts);
        assertThat(parts).isEmpty();
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：read=1 活跃 + 空 parts → 'Recalling 1 team memory'（单数）")
    void append_readOneActiveFirst() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(1, 0, 0), true, parts);
        assertThat(parts).containsExactly("Recalling 1 team memory");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：read=2 → 'Recalling 2 team memories'（复数）")
    void append_readTwoPlural() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(2, 0, 0), true, parts);
        assertThat(parts).containsExactly("Recalling 2 team memories");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：非活跃 → 'Recalled 1 team memory'（完成时）")
    void append_readInactive() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(1, 0, 0), false, parts);
        assertThat(parts).containsExactly("Recalled 1 team memory");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：search 段固定 'team memories' 无计数（CC :63-64）")
    void append_searchNoCount() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(0, 3, 0), true, parts);
        assertThat(parts).containsExactly("Searching team memories");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：search 非活跃 → 'Searched team memories'")
    void append_searchInactive() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(0, 1, 0), false, parts);
        assertThat(parts).containsExactly("Searched team memories");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：write=1 → 'Writing 1 team memory'；write=2 → 复数")
    void append_writeCounts() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(0, 0, 1), true, parts);
        assertThat(parts).containsExactly("Writing 1 team memory");

        parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(0, 0, 2), false, parts);
        assertThat(parts).containsExactly("Wrote 2 team memories");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：read+search+write 全开 → 首段大写、后续小写、顺序 read→search→write")
    void append_allCounts_orderAndCase() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(1, 1, 1), true, parts);
        assertThat(parts).containsExactly(
            "Recalling 1 team memory",
            "searching team memories",
            "writing 1 team memory");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：非活跃全开 → 完成时首段大写、后续小写")
    void append_allCountsInactive() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(2, 2, 2), false, parts);
        assertThat(parts).containsExactly(
            "Recalled 2 team memories",
            "searched team memories",
            "wrote 2 team memories");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：前置已有 parts（非空）→ 全小写（parts.length===0 判定）")
    void append_withExistingParts_lowercase() {
        List<String> parts = new ArrayList<>(List.of("Read 1 file"));
        ops.appendTeamMemorySummaryParts(new TeamMemoryOps.TeamMemoryCounts(1, 0, 0), true, parts);
        assertThat(parts).containsExactly("Read 1 file", "recalling 1 team memory");
    }

    @Test
    @DisplayName("appendTeamMemorySummaryParts：of 工厂 null → 0 归一（CC ?? 0）")
    void append_ofFactoryNullNormalizesZero() {
        List<String> parts = new ArrayList<>();
        ops.appendTeamMemorySummaryParts(TeamMemoryOps.TeamMemoryCounts.of(null, 1, null), true, parts);
        assertThat(parts).containsExactly("Searching team memories");
    }

    private String teamFile() {
        return paths.getTeamMemPath() + "MEMORY.md";
    }
}
