package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CountLinesChanged} 单测 · 对齐 CC {@code countLinesChanged}
 * （utils/diff.ts:49-64）—— patch 前缀 +/− 计数；新建文件全行算新增。
 *
 * <p>WHY（规则九）：计数语义是 CC 预算消费（totalLinesChanged / tool_result_budget）的前置，
 * Java 端暂以数据流日志消费（compact 预算接线后续 session），本测试锁定计数规则防止实现漂移。
 */
@DisplayName("CountLinesChanged · +/− 前缀计数 + 新建文件全行计数（CC utils/diff.ts:49-64）")
class CountLinesChangedTest {

    @Test
    @DisplayName("patch 按 + / − 前缀计数")
    void countsPatchPrefixLines() {
        List<StructuredPatchHunk> patch = List.of(new StructuredPatchHunk(
            1, 3, 1, 3,
            List.of(" line1", "-old", "+new", " line3")));
        long[] counts = CountLinesChanged.countLinesChanged(patch, null);
        assertThat(counts[0]).isEqualTo(1);  // additions
        assertThat(counts[1]).isEqualTo(1);  // removals
    }

    @Test
    @DisplayName("空 patch + newFileContent → 全部行算新增（含尾随换行产生的空行）")
    void emptyPatchCountsAllNewFileLines() {
        // CC diff.ts:55-58 newFileContent.split(/\r?\n/).length → "a\nb\n" = 3
        long[] counts = CountLinesChanged.countLinesChanged(List.of(), "a\nb\n");
        assertThat(counts[0]).isEqualTo(3);
        assertThat(counts[1]).isEqualTo(0);
    }

    @Test
    @DisplayName("空 patch + null → 0/0（CC else 分支对空 patch 的归约）")
    void emptyPatchNullContentCountsZero() {
        long[] counts = CountLinesChanged.countLinesChanged(List.of(), null);
        assertThat(counts[0]).isZero();
        assertThat(counts[1]).isZero();
    }
}
