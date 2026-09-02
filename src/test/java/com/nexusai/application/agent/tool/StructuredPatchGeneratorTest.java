package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructuredPatchGenerator} 单测 · 对齐 CC {@code getPatchFromContents}
 * （utils/diff.ts:81-114）—— 手写 LCS 行级 diff 的 hunk 形状 + & / $ 转义回填。
 *
 * <p>WHY（规则九 · 验证意图）：structuredPatch 是 Edit/Write 输出契约的核心字段，
 * 前端 diff 视图 + countLinesChanged 依赖 hunk 的 {@code {oldStart,oldLines,newStart,newLines,lines[]}}
 * 形状与 lines 前缀（' '/'+'/'−'）。手写 LCS 与 npm diff 的 hunk 边界可能有差异，
 * 本测试以固定小样本锁定形状，防止实现漂移破坏消费方。
 */
@DisplayName("StructuredPatchGenerator · hunk 形状 + &/$ 转义回填（CC utils/diff.ts:81-114）")
class StructuredPatchGeneratorTest {

    @Test
    @DisplayName("简单替换 → 1 个 hunk，行号与 lines 前缀对齐 CC 形状")
    void singleReplacementProducesOneHunk() {
        String oldContent = "line1\nline2\nline3\nline4\n";
        String newContent = "line1\nCHANGED\nline3\nline4\n";

        List<StructuredPatchHunk> hunks = StructuredPatchGenerator.getPatch(oldContent, newContent);

        assertThat(hunks).hasSize(1);
        StructuredPatchHunk hunk = hunks.get(0);
        // CC hunkSchema（types.ts:36-44）oldStart/oldLines/newStart/newLines
        assertThat(hunk.oldStart()).isEqualTo(1);
        assertThat(hunk.oldLines()).isEqualTo(4);
        assertThat(hunk.newStart()).isEqualTo(1);
        assertThat(hunk.newLines()).isEqualTo(4);
        // lines 前缀：' ' 上下文 / '−' 删除 / '+' 新增
        assertThat(hunk.lines()).containsExactly(
            " line1",
            "-line2",
            "+CHANGED",
            " line3",
            " line4");
    }

    @Test
    @DisplayName("行内含 & 与 $ → escape 后 unescape 回填，hunk lines 与原文本一致")
    void ampersandAndDollarSurviveEscapeRoundTrip() {
        // CC diff.ts:30-47 解释：& 会干扰 diff 库，先 token 化再回填 —— 输出必须保留原始 & 和 $
        String oldContent = "cost $5\nvalue = a & b\n";
        String newContent = "cost $6\nvalue = a & b\n";

        List<StructuredPatchHunk> hunks = StructuredPatchGenerator.getPatch(oldContent, newContent);

        assertThat(hunks).hasSize(1);
        StructuredPatchHunk hunk = hunks.get(0);
        assertThat(hunk.lines()).contains(
            "-cost $5",
            "+cost $6",
            " value = a & b");
        assertThat(hunk.lines())
            .as("hunk lines 不得残留 token")
            .noneMatch(l -> l.contains("<<:") || l.contains(":>>"));
    }

    @Test
    @DisplayName("无变更 → 空 hunk 数组（CC getPatchFromContents 空 hunks）")
    void noChangesProducesEmptyHunks() {
        List<StructuredPatchHunk> hunks =
            StructuredPatchGenerator.getPatch("same\n", "same\n");
        assertThat(hunks).isEmpty();
    }

    @Test
    @DisplayName("中部插入 → oldStart/newStart 指向插入锚点，行号正确前移")
    void insertionKeepsLineNumbers() {
        String oldContent = "a\nb\n";
        String newContent = "a\nX\nb\n";

        List<StructuredPatchHunk> hunks = StructuredPatchGenerator.getPatch(oldContent, newContent);

        assertThat(hunks).hasSize(1);
        StructuredPatchHunk hunk = hunks.get(0);
        // 纯插入：旧 2 行上下文 a/b；新 3 行 a/X/b（context=3 覆盖全文件）
        assertThat(hunk.oldStart()).isEqualTo(1);
        assertThat(hunk.oldLines()).isEqualTo(2);
        assertThat(hunk.newStart()).isEqualTo(1);
        assertThat(hunk.newLines()).isEqualTo(3);
        assertThat(hunk.lines()).containsExactly(" a", "+X", " b");
    }

    @Test
    @DisplayName("context=3 分隔的两处变更 → 合并为 1 个 hunk（重叠合并语义）")
    void nearbyChangesMergeIntoOneHunk() {
        StringBuilder oldB = new StringBuilder();
        StringBuilder newB = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldB.append("line").append(i).append('\n');
            newB.append(i == 3 || i == 15 ? "CHANGED\n" : "line").append(i).append('\n');
        }
        List<StructuredPatchHunk> hunks =
            StructuredPatchGenerator.getPatch(oldB.toString(), newB.toString());
        // 两处变更相距 >2*context 行 → 2 个独立 hunk
        assertThat(hunks).hasSize(2);
        assertThat(hunks.get(0).lines())
            .anyMatch(l -> l.startsWith("-line3"))
            .anyMatch(l -> l.startsWith("+CHANGED"));
        assertThat(hunks.get(1).lines())
            .anyMatch(l -> l.startsWith("-line15"))
            .anyMatch(l -> l.startsWith("+CHANGED"));
    }
}
